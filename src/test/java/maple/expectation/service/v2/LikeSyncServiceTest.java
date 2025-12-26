package maple.expectation.service.v2;

import com.github.benmanes.caffeine.cache.Cache;
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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LikeSyncServiceTest {

    private LikeSyncService likeSyncService;

    @Mock
    private LikeBufferStorage likeBufferStorage;

    @Mock
    private LikeSyncExecutor syncExecutor;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private Cache<String, AtomicLong> mockCache;

    private final Retry likeSyncRetry = Retry.ofDefaults("testRetry");

    @BeforeEach
    void setUp() {
        likeSyncService = new LikeSyncService(
                likeBufferStorage,
                syncExecutor,
                eventPublisher,
                likeSyncRetry
        );
    }

    @Test
    @DisplayName("시나리오 1: 최종 실패 시 이벤트를 발행하고 데이터를 롤백한다")
    void syncLikes_ShouldPublishEvent_OnPersistentFailure() {
        // [Given]
        String userIgn = "Gamer";
        AtomicLong counter = new AtomicLong(10);
        ConcurrentMap<String, AtomicLong> bufferMap = new ConcurrentHashMap<>();
        bufferMap.put(userIgn, counter);

        given(likeBufferStorage.getCache()).willReturn(mockCache);
        given(mockCache.asMap()).willReturn(bufferMap);

        // Executor가 에러를 던지도록 설정 (Retry가 실제 객체이므로 내부적으로 재시도를 시뮬레이션함)
        willThrow(new RuntimeException("DB 연결 실패"))
                .given(syncExecutor).executeIncrement(anyString(), anyLong());

        // [When]
        likeSyncService.syncLikesToDatabase();

        // [Then]
        // 1. Executor 호출 확인 (Retry 설정에 따라 호출 횟수가 결정됨)
        verify(syncExecutor, atLeastOnce()).executeIncrement(eq(userIgn), anyLong());

        // 2. 최종 실패 시 이벤트 발행 확인
        verify(eventPublisher, times(1)).publishEvent(any(LikeSyncFailedEvent.class));

        // 3. 데이터 복구 확인 (롤백되어 10L 유지)
        assertThat(counter.get()).isEqualTo(10L);
    }

    @Test
    @DisplayName("시나리오 2: 성공 시 데이터가 정상 반영된다 (카운트 0)")
    void syncLikes_ShouldSucceed() {
        // [Given]
        String userIgn = "Gamer";
        AtomicLong counter = new AtomicLong(5);
        ConcurrentMap<String, AtomicLong> bufferMap = new ConcurrentHashMap<>();
        bufferMap.put(userIgn, counter);

        given(likeBufferStorage.getCache()).willReturn(mockCache);
        given(mockCache.asMap()).willReturn(bufferMap);

        // [When]
        likeSyncService.syncLikesToDatabase();

        // [Then]
        // 1. Executor가 정상적으로 호출되었는지 확인
        verify(syncExecutor, times(1)).executeIncrement(eq(userIgn), eq(5L));

        // 2. 성공했으므로 이벤트 발행은 없어야 함
        verify(eventPublisher, never()).publishEvent(any());

        // 🚀 [검증] 드디어 0L로 정상 반영!
        assertThat(counter.get()).isZero();
    }
}