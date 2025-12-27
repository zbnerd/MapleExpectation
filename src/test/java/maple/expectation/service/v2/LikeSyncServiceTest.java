package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.service.v2.like.event.LikeSyncFailedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
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
    @Mock private ApplicationEventPublisher eventPublisher;

    // 💡 Redis 관련 모킹 추가
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOperations;

    private final Retry likeSyncRetry = Retry.ofDefaults("testRetry");
    private static final String REDIS_HASH_KEY = "buffer:likes";

    @BeforeEach
    void setUp() {
        // 💡 새 생성자 파라미터에 맞춰 redisTemplate 주입
        likeSyncService = new LikeSyncService(
                likeBufferStorage,
                syncExecutor,
                eventPublisher,
                likeSyncRetry,
                redisTemplate
        );

        // Redis Hash 연산을 위한 기본 설정
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("시나리오 1: Redis 데이터를 DB에 반영 실패 시, Redis 값을 차감하지 않고 알림을 보낸다")
    void syncRedisToDatabase_FailureScenario() {
        // [Given] Redis에 Gamer의 좋아요 10개가 있는 상황
        String userIgn = "Gamer";
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

        // 2. 실패했으므로 Redis 수치를 깎는 increment(-10) 호출이 절대 없어야 함 🛡️
        verify(hashOperations, never()).increment(anyString(), any(), anyLong());

        // 3. 에러 이벤트 발행 확인
        verify(eventPublisher, times(1)).publishEvent(any(LikeSyncFailedEvent.class));
    }

    @Test
    @DisplayName("시나리오 2: Redis 데이터를 DB에 반영 성공 시, Redis에서 해당 수치만큼 정확히 차감한다")
    void syncRedisToDatabase_SuccessScenario() {
        // [Given] Redis에 좋아요 5개가 쌓여있음
        String userIgn = "Gamer";
        Map<Object, Object> redisData = Map.of(userIgn, "5");
        given(hashOperations.entries(REDIS_HASH_KEY)).willReturn(redisData);

        // [When]
        likeSyncService.syncRedisToDatabase();

        // [Then]
        // 1. DB 반영 성공 확인
        verify(syncExecutor, times(1)).executeIncrement(eq(userIgn), eq(5L));

        // 2. 🚀 핵심: 성공했으므로 Redis에서 -5를 더해(차감) 0으로 만듦
        verify(hashOperations, times(1)).increment(REDIS_HASH_KEY, userIgn, -5L);

        // 3. 성공 시 이벤트 발행 없음
        verify(eventPublisher, never()).publishEvent(any());
    }
}