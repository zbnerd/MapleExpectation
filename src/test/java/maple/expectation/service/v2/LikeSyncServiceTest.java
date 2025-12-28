package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import maple.expectation.service.v2.cache.LikeBufferStorage;
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

    // 💡 StringRedisTemplate의 opsForHash() 반환 타입에 맞춰 모킹
    @Mock private HashOperations<String, Object, Object> hashOperations;

    private final Retry likeSyncRetry = Retry.ofDefaults("testRetry");
    private static final String REDIS_HASH_KEY = "buffer:likes";

    @BeforeEach
    void setUp() {
        // 🚀 승준님의 서비스 필드 순서와 100% 일치 (총 4개)
        likeSyncService = new LikeSyncService(
                likeBufferStorage, // 1
                syncExecutor,      // 2
                redisTemplate,     // 3
                likeSyncRetry      // 4
        );

        // Redis 연산 모킹 설정
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("시나리오 1: Redis 데이터를 DB에 반영 실패 시, Redis 값을 차감하지 않는다")
    void syncRedisToDatabase_FailureScenario() {
        // [Given]
        String userIgn = "Gamer";
        // 서비스의 fetchRedisEntries가 Map<Object, Object>를 반환하므로 타입을 맞춤
        Map<Object, Object> redisData = Map.of(userIgn, "10");
        given(hashOperations.entries(REDIS_HASH_KEY)).willReturn(redisData);

        // DB 반영 시 에러 발생 시뮬레이션
        willThrow(new RuntimeException("DB Connection Fail"))
                .given(syncExecutor).executeIncrement(eq(userIgn), eq(10L));

        // [When]
        likeSyncService.syncRedisToDatabase();

        // [Then]
        // 1. DB 반영 시도 확인
        verify(syncExecutor, atLeastOnce()).executeIncrement(eq(userIgn), eq(10L));

        // 2. 🛡️ 중요: 실패했으므로 Redis 수치를 깎는(차감하는) 호출이 없어야 함
        verify(hashOperations, never()).increment(anyString(), any(), anyLong());

        // 💡 이제 서비스에 eventPublisher가 없으므로 알림 발송 검증은 삭제함
    }

    @Test
    @DisplayName("시나리오 2: Redis 데이터를 DB에 반영 성공 시, Redis에서 해당 수치만큼 차감한다")
    void syncRedisToDatabase_SuccessScenario() {
        // [Given]
        String userIgn = "Gamer";
        Map<Object, Object> redisData = Map.of(userIgn, "5");
        given(hashOperations.entries(REDIS_HASH_KEY)).willReturn(redisData);

        // [When]
        likeSyncService.syncRedisToDatabase();

        // [Then]
        // 1. DB 반영 성공 확인
        verify(syncExecutor, times(1)).executeIncrement(eq(userIgn), eq(5L));

        // 2. ✅ 성공했으므로 Redis에서 -5 차감 확인
        verify(hashOperations, times(1)).increment(REDIS_HASH_KEY, userIgn, -5L);
    }
}