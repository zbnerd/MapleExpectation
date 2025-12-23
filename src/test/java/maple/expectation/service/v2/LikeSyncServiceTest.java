package maple.expectation.service.v2;

import com.github.benmanes.caffeine.cache.Cache;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LikeSyncServiceTest {

    @InjectMocks
    private LikeSyncService likeSyncService;

    @Mock
    private LikeBufferStorage likeBufferStorage;

    @Mock
    private GameCharacterRepository gameCharacterRepository;

    @Mock
    private DiscordAlertService discordAlertService;

    @Mock
    private Cache<String, AtomicLong> mockCache;

    @Test
    @DisplayName("시나리오 1: DB 장애 시 3회 재시도 후 최종 실패하면 알림을 보내고 데이터를 롤백한다")
    void syncLikes_ShouldRetryAndSendAlert_OnPersistentFailure() {
        // [Given]
        String userIgn = "Gamer";
        AtomicLong counter = new AtomicLong(10);

        // 💡 해결책 1: ConcurrentMap 타입으로 선언하여 Caffeine 타입에 맞춤
        ConcurrentMap<String, AtomicLong> bufferMap = new ConcurrentHashMap<>();
        bufferMap.put(userIgn, counter);

        given(likeBufferStorage.getCache()).willReturn(mockCache);
        given(mockCache.asMap()).willReturn(bufferMap);

        // 💡 해결책 2: void 메서드 스터빙 시 doThrow를 사용하며, Long 객체 타입은 any(Long.class)가 더 안전함
        doThrow(new RuntimeException("DB 연결 실패"))
                .when(gameCharacterRepository).incrementLikeCount(anyString(), any(Long.class));

        // [When]
        likeSyncService.syncLikesToDatabase();

        // [Then]
        verify(gameCharacterRepository, times(3)).incrementLikeCount(eq(userIgn), any(Long.class));
        verify(discordAlertService, times(1)).sendCriticalAlert(anyString(), anyString(), any());
        assertThat(counter.get()).isEqualTo(10L);
    }

    @Test
    @DisplayName("시나리오 2: 1, 2회차에 실패하더라도 3회차에 성공하면 데이터는 정상 반영된다")
    void syncLikes_ShouldSucceed_OnThirdAttempt() {
        // [Given]
        String userIgn = "Gamer";
        AtomicLong counter = new AtomicLong(5);

        ConcurrentMap<String, AtomicLong> bufferMap = new ConcurrentHashMap<>();
        bufferMap.put(userIgn, counter);

        given(likeBufferStorage.getCache()).willReturn(mockCache);
        given(mockCache.asMap()).willReturn(bufferMap);

        // 💡 1, 2회차는 에러, 3회차는 성공 시뮬레이션
        doThrow(new RuntimeException("1차 실패"))
                .doThrow(new RuntimeException("2차 실패"))
                .doNothing()
                .when(gameCharacterRepository).incrementLikeCount(anyString(), any(Long.class));

        // [When]
        likeSyncService.syncLikesToDatabase();

        // [Then]
        verify(gameCharacterRepository, times(3)).incrementLikeCount(anyString(), any(Long.class));
        verify(discordAlertService, never()).sendCriticalAlert(anyString(), anyString(), any());
        assertThat(counter.get()).isEqualTo(0L);
    }
}