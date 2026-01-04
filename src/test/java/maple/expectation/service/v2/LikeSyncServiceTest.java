package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import maple.expectation.global.executor.DefaultLogicExecutor;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

/**
 * 🚀 [Fix] 컴파일 에러를 해결하고 비동기 로직 실행을 보장하는 최종 테스트 코드
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

    // 실제 객체를 사용하여 내부 람다 실행 보장
    private DefaultLogicExecutor logicExecutor;
    @Mock private ExceptionTranslator exceptionTranslator;

    private final Retry likeSyncRetry = Retry.ofDefaults("testRetry");
    private static final String REDIS_HASH_KEY = "buffer:likes";

    @BeforeEach
    void setUp() {
        // 🚀 [해결 1] 생성자 파라미터를 1개로 수정 (image_013d2a 대응)
        logicExecutor = new DefaultLogicExecutor(exceptionTranslator);

        likeSyncService = new LikeSyncService(
                likeBufferStorage,
                syncExecutor,
                redisTemplate,
                redisBufferRepository,
                likeSyncRetry,
                shutdownDataPersistenceService,
                logicExecutor
        );

        // 🚀 [해결 2] when() 뒤에는 thenReturn()을 사용 (image_013d48 대응)
        lenient().when(redisTemplate.hasKey(anyString())).thenReturn(true);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("성공 시나리오: Rename 후 데이터를 DB에 반영하고 전역 카운터를 차감한다")
    void syncRedisToDatabase_SuccessScenario() {
        // [Given]
        String userIgn = "Gamer";
        Map<Object, Object> redisData = Map.of(userIgn, "5");

        // BDD 스타일(given) 유지
        given(hashOperations.entries(anyString())).willReturn(redisData);

        // [When]
        likeSyncService.syncRedisToDatabase();

        // [Then]
        verify(redisTemplate, times(1)).rename(eq(REDIS_HASH_KEY), anyString());
        verify(syncExecutor, times(1)).executeIncrement(eq(userIgn), eq(5L));
        verify(redisBufferRepository, times(1)).decrementGlobalCount(5L);
        verify(redisTemplate, times(1)).delete(anyString());
    }

    @Test
    @DisplayName("실패 시나리오: DB 반영 실패 시 전역 카운터를 차감하지 않는다")
    void syncRedisToDatabase_FailureScenario() {
        // [Given]
        String userIgn = "Gamer";
        Map<Object, Object> redisData = Map.of(userIgn, "10");

        given(hashOperations.entries(anyString())).willReturn(redisData);

        // 🚀 [해결 3] 매처를 메서드 인자에 직접 사용하여 mismatch 해결
        willThrow(new RuntimeException("DB Fail"))
                .given(syncExecutor).executeIncrement(anyString(), anyLong());

        // [When]
        likeSyncService.syncRedisToDatabase();

        // [Then]
        // 비즈니스 로직: 실패 시 차감하지 않음
        verify(redisBufferRepository, never()).decrementGlobalCount(anyLong());
        // 기술적 로직: 실패해도 임시 키 삭제(Clean-up)는 수행되어야 함
        verify(redisTemplate, times(1)).delete(anyString());
    }
}