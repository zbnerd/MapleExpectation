package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import maple.expectation.repository.v2.RedisBufferRepository; // ✅ 추가
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

@ExtendWith(MockitoExtension.class)
class LikeSyncServiceTest {

    private LikeSyncService likeSyncService;

    @Mock private LikeBufferStorage likeBufferStorage;
    @Mock private LikeSyncExecutor syncExecutor;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisBufferRepository redisBufferRepository; // ✅ 추가: 리포지토리 모킹
    @Mock private ShutdownDataPersistenceService shutdownDataPersistenceService; // ✅ 추가: Shutdown 데이터 서비스 모킹
    @Mock private HashOperations<String, Object, Object> hashOperations;

    private final Retry likeSyncRetry = Retry.ofDefaults("testRetry");
    private static final String REDIS_HASH_KEY = "buffer:likes";

    @BeforeEach
    void setUp() {
        // 🚀 핵심: 변경된 6개의 파라미터 순서에 맞춰 생성자 호출
        likeSyncService = new LikeSyncService(
                likeBufferStorage,                // 1
                syncExecutor,                     // 2
                redisTemplate,                    // 3
                redisBufferRepository,            // 4
                likeSyncRetry,                    // 5
                shutdownDataPersistenceService    // 6 (추가됨)
        );

        // Redis 연산 기본 설정
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("성공 시나리오: Rename 후 데이터를 DB에 반영하고 전역 카운터를 차감한다")
    void syncRedisToDatabase_SuccessScenario() {
        // [Given]
        String userIgn = "Gamer";
        Map<Object, Object> redisData = Map.of(userIgn, "5");

        // 💡 Rename 전략 대응: 키가 존재한다고 가정
        given(redisTemplate.hasKey(REDIS_HASH_KEY)).willReturn(true);
        // 임시 키(anyString)에서 데이터를 읽어온다고 설정
        given(hashOperations.entries(anyString())).willReturn(redisData);

        // [When]
        likeSyncService.syncRedisToDatabase();

        // [Then]
        // 1. Rename 명령어 실행 확인
        verify(redisTemplate, times(1)).rename(eq(REDIS_HASH_KEY), anyString());

        // 2. DB 반영 성공 확인
        verify(syncExecutor, times(1)).executeIncrement(eq(userIgn), eq(5L));

        // 3. ✅ 중요: 전역 카운터 차감(decrement)이 호출되었는지 확인
        verify(redisBufferRepository, times(1)).decrementGlobalCount(5L);

        // 4. 임시 키 삭제 확인
        verify(redisTemplate, times(1)).delete(anyString());
    }

    @Test
    @DisplayName("실패 시나리오: DB 반영 실패 시 전역 카운터를 차감하지 않는다")
    void syncRedisToDatabase_FailureScenario() {
        // [Given]
        String userIgn = "Gamer";
        Map<Object, Object> redisData = Map.of(userIgn, "10");

        given(redisTemplate.hasKey(REDIS_HASH_KEY)).willReturn(true);
        given(hashOperations.entries(anyString())).willReturn(redisData);

        // DB 반영 시 에러 발생 시뮬레이션
        willThrow(new RuntimeException("DB Fail"))
                .given(syncExecutor).executeIncrement(anyString(), anyLong());

        // [When]
        likeSyncService.syncRedisToDatabase();

        // [Then]
        // 🛡️ 실패했으므로 전역 카운터 차감이 호출되지 않아야 함
        verify(redisBufferRepository, never()).decrementGlobalCount(anyLong());
    }
}