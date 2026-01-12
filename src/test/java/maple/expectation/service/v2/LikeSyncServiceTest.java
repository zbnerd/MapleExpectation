package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.global.redis.script.LikeAtomicOperations;
import maple.expectation.global.redis.script.LuaScripts;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

/**
 * LikeSyncService 테스트 (Issue #147 원자적 연산 적용)
 *
 * <p>LogicExecutor 계약 기반 테스트로 비즈니스 로직을 검증합니다.</p>
 */
@ExtendWith(MockitoExtension.class)
class LikeSyncServiceTest {

    private LikeSyncService likeSyncService;

    @Mock private LikeBufferStorage likeBufferStorage;
    @Mock private LikeSyncExecutor syncExecutor;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private LikeAtomicOperations atomicOperations;
    @Mock private ShutdownDataPersistenceService shutdownDataPersistenceService;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @Mock private LogicExecutor executor;

    private final Retry likeSyncRetry = Retry.ofDefaults("testRetry");

    @BeforeEach
    void setUp() {
        setupLogicExecutorMocks();

        likeSyncService = new LikeSyncService(
                likeBufferStorage,
                syncExecutor,
                redisTemplate,
                atomicOperations,
                likeSyncRetry,
                shutdownDataPersistenceService,
                executor
        );

        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    private void setupLogicExecutorMocks() {
        // [패턴 1] executeWithFinally: task 실행 후 finalizer 반드시 실행
        lenient().doAnswer(inv -> {
            ThrowingSupplier<?> task = inv.getArgument(0);
            Runnable finalizer = inv.getArgument(1);
            AtomicBoolean finalizerRan = new AtomicBoolean(false);

            try {
                return task.get();
            } finally {
                if (finalizerRan.compareAndSet(false, true)) {
                    finalizer.run();
                }
            }
        }).when(executor).executeWithFinally(any(), any(), any());

        // [패턴 2] executeVoid: task 실행 (반환값 무시)
        lenient().doAnswer(inv -> {
            ThrowingRunnable task = inv.getArgument(0);
            task.run();
            return null;
        }).when(executor).executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));

        // [패턴 3] executeOrDefault: task 실행, 예외 시 기본값 반환
        lenient().doAnswer(inv -> {
            ThrowingSupplier<?> task = inv.getArgument(0);
            Object defaultValue = inv.getArgument(1);
            try {
                return task.get();
            } catch (Error err) {
                throw err;
            } catch (Throwable e) {
                return defaultValue;
            }
        }).when(executor).executeOrDefault(any(), any(), any());

        // [패턴 4] executeOrCatch: Task 실행 시도 -> 예외 시 Handler 실행
        lenient().doAnswer(inv -> {
            ThrowingSupplier<?> task = inv.getArgument(0);
            Function<Throwable, ?> handler = inv.getArgument(1);
            try {
                return task.get();
            } catch (Error err) {
                throw err;
            } catch (Throwable t) {
                return handler.apply(t);
            }
        }).when(executor).executeOrCatch(any(), any(), any());
    }

    @Test
    @DisplayName("성공 시나리오: Rename 후 데이터를 DB에 반영하고 원자적으로 카운터를 차감한다")
    void syncRedisToDatabase_SuccessScenario() {
        // [Given]
        String userIgn = "Gamer";
        Map<Object, Object> redisData = Map.of(userIgn, "5");

        given(redisTemplate.hasKey(LuaScripts.Keys.HASH)).willReturn(true);
        given(hashOperations.entries(anyString())).willReturn(redisData);
        given(redisTemplate.hasKey(argThat(key -> key != null && key.contains(":sync:")))).willReturn(false);

        // 원자 연산 성공
        given(atomicOperations.atomicDeleteAndDecrement(anyString(), eq(userIgn), eq(5L))).willReturn(1L);

        // [When]
        likeSyncService.syncRedisToDatabase();

        // [Then]
        verify(redisTemplate, times(1)).rename(eq(LuaScripts.Keys.HASH), anyString());
        verify(syncExecutor, times(1)).executeIncrement(eq(userIgn), eq(5L));

        // 원자적 삭제 및 카운터 차감 호출 확인
        verify(atomicOperations, times(1)).atomicDeleteAndDecrement(anyString(), eq(userIgn), eq(5L));

        // 정상 경로는 doSyncProcess에서 tempKey 삭제
        verify(redisTemplate, times(1)).delete(argThat((String k) -> k != null && k.contains(":sync:")));
    }

    @Test
    @DisplayName("실패 시나리오: DB 반영 실패 시 원자적으로 원본 버퍼에 복구한다")
    void syncRedisToDatabase_FailureScenario() {
        // [Given]
        String userIgn = "Gamer";
        Map<Object, Object> redisData = Map.of(userIgn, "10");

        given(redisTemplate.hasKey(LuaScripts.Keys.HASH)).willReturn(true);
        given(hashOperations.entries(anyString())).willReturn(redisData);
        given(redisTemplate.hasKey(argThat(key -> key != null && key.contains(":sync:")))).willReturn(false);

        // DB 실패
        willThrow(new RuntimeException("DB Fail"))
                .given(syncExecutor).executeIncrement(anyString(), anyLong());

        // 원자적 복구 성공
        given(atomicOperations.atomicCompensation(anyString(), eq(userIgn), eq(10L))).willReturn(true);

        // [When]
        likeSyncService.syncRedisToDatabase();

        // [Then]
        // 원자적 삭제 및 차감은 호출되지 않음 (DB 실패이므로)
        verify(atomicOperations, never()).atomicDeleteAndDecrement(anyString(), anyString(), anyLong());

        // 원자적 복구 호출 확인
        verify(atomicOperations, times(1)).atomicCompensation(anyString(), eq(userIgn), eq(10L));

        // doSyncProcess에서 tempKey 삭제
        verify(redisTemplate, times(1)).delete(argThat((String k) -> k != null && k.contains(":sync:")));
    }

    @Test
    @DisplayName("멱등성: 이미 처리된 엔트리에 대해 중복 차감 없음")
    void syncRedisToDatabase_IdempotentDeleteAndDecrement() {
        // [Given]
        String userIgn = "Gamer";
        Map<Object, Object> redisData = Map.of(userIgn, "5");

        given(redisTemplate.hasKey(LuaScripts.Keys.HASH)).willReturn(true);
        given(hashOperations.entries(anyString())).willReturn(redisData);
        given(redisTemplate.hasKey(argThat(key -> key != null && key.contains(":sync:")))).willReturn(false);

        // 멱등성: 이미 삭제됨 (deleted=0 반환)
        given(atomicOperations.atomicDeleteAndDecrement(anyString(), eq(userIgn), eq(5L))).willReturn(0L);

        // [When]
        likeSyncService.syncRedisToDatabase();

        // [Then]
        verify(syncExecutor, times(1)).executeIncrement(eq(userIgn), eq(5L));

        // 멱등성 확인: deleted=0이면 DECRBY 스킵됨 (Lua Script 내부에서)
        verify(atomicOperations, times(1)).atomicDeleteAndDecrement(anyString(), eq(userIgn), eq(5L));
    }
}
