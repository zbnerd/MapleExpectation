package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.repository.v2.RedisBufferRepository;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

/**
 * ✅ LogicExecutor 계약 기반 테스트 (본연의 비즈니스 로직 검증)
 */
@ExtendWith(MockitoExtension.class)
class LikeSyncServiceTest {

    private LikeSyncService likeSyncService;

    @Mock private LikeBufferStorage likeBufferStorage;
    @Mock private LikeSyncExecutor syncExecutor;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisBufferRepository redisBufferRepository;
    @Mock private ShutdownDataPersistenceService shutdownDataPersistenceService;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @Mock private LogicExecutor executor;

    private final Retry likeSyncRetry = Retry.ofDefaults("testRetry");
    private static final String REDIS_HASH_KEY = "buffer:likes";

    @BeforeEach
    void setUp() {
        // ✅ LogicExecutor 계약 stub (3가지 패턴)

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

        // [패턴 3] executeOrDefault: task 실행, 예외 시 기본값 반환 (Error는 즉시 rethrow)
        lenient().doAnswer(inv -> {
            ThrowingSupplier<?> task = inv.getArgument(0);
            Object defaultValue = inv.getArgument(1);
            try {
                return task.get();
            } catch (Error err) {
                throw err; // Error는 복구 금지
            } catch (Throwable e) {
                return defaultValue;
            }
        }).when(executor).executeOrDefault(any(), any(), any());

        likeSyncService = new LikeSyncService(
                likeBufferStorage,
                syncExecutor,
                redisTemplate,
                redisBufferRepository,
                likeSyncRetry,
                shutdownDataPersistenceService,
                executor
        );

        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("성공 시나리오: Rename 후 데이터를 DB에 반영하고 전역 카운터를 차감한다")
    void syncRedisToDatabase_SuccessScenario() {
        // [Given]
        String userIgn = "Gamer";
        Map<Object, Object> redisData = Map.of(userIgn, "5");

        // BEFORE rename: REDIS_HASH_KEY 존재
        given(redisTemplate.hasKey(REDIS_HASH_KEY)).willReturn(true);
        given(hashOperations.entries(anyString())).willReturn(redisData);
        // AFTER doSyncProcess delete: tempKey 존재하지 않음 (cleanup skip)
        given(redisTemplate.hasKey(argThat(key -> key.contains(":sync:")))).willReturn(false);

        // [When]
        likeSyncService.syncRedisToDatabase();

        // [Then]
        verify(redisTemplate, times(1)).rename(eq(REDIS_HASH_KEY), anyString());
        verify(syncExecutor, times(1)).executeIncrement(eq(userIgn), eq(5L));
        verify(redisBufferRepository, times(1)).decrementGlobalCount(5L);

        // ✅ [핵심] 성공한 항목은 tempKey field에서 삭제됨 (HDEL)
        verify(hashOperations, times(1)).delete(anyString(), eq(userIgn));

        // ✅ [핵심] 정상 경로는 doSyncProcess에서 tempKey 삭제 (cleanup skip)
        verify(redisTemplate, times(1)).delete(argThat((String k) -> k.contains(":sync:")));
    }

    @Test
    @DisplayName("실패 시나리오: DB 반영 실패 시 전역 카운터를 차감하지 않는다")
    void syncRedisToDatabase_FailureScenario() {
        // [Given]
        String userIgn = "Gamer";
        Map<Object, Object> redisData = Map.of(userIgn, "10");

        given(redisTemplate.hasKey(REDIS_HASH_KEY)).willReturn(true);
        given(hashOperations.entries(anyString())).willReturn(redisData);
        // AFTER doSyncProcess: tempKey는 여전히 존재 (실패 항목이 남아있지 않음)
        // 실패 항목은 doSyncProcess에서 즉시 원본 버퍼로 복구했으므로 tempKey는 비어있음
        given(redisTemplate.hasKey(argThat(key -> key.contains(":sync:")))).willReturn(false);

        willThrow(new RuntimeException("DB Fail"))
                .given(syncExecutor).executeIncrement(anyString(), anyLong());

        // [When]
        likeSyncService.syncRedisToDatabase();

        // [Then]
        // 비즈니스 로직: 실패 시 차감하지 않음
        verify(redisBufferRepository, never()).decrementGlobalCount(anyLong());

        // ✅ [핵심] 실패 항목은 원본 버퍼로 즉시 복구
        verify(hashOperations, times(1)).increment(eq(REDIS_HASH_KEY), eq(userIgn), eq(10L));

        // ✅ [핵심] 실패 항목도 tempKey field에서 제거됨 (HDEL)
        verify(hashOperations, times(1)).delete(anyString(), eq(userIgn));

        // ✅ [핵심] doSyncProcess에서 tempKey 삭제 (cleanup skip)
        verify(redisTemplate, times(1)).delete(argThat((String k) -> k.contains(":sync:")));
    }

}
