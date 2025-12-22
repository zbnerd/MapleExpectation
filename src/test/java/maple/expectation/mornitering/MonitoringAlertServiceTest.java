package maple.expectation.mornitering;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitoringAlertServiceTest {

    @Mock
    private LikeBufferStorage likeBufferStorage;

    @Mock
    private DiscordAlertService discordAlertService;

    @InjectMocks
    private MonitoringAlertService monitoringAlertService;

    private Cache<String, AtomicLong> testCache;

    @BeforeEach
    void setUp() {
        // 실제 Caffeine 캐시를 생성하여 Mock 동작 설정
        testCache = Caffeine.newBuilder().build();
        given(likeBufferStorage.getCache()).willReturn(testCache);
    }

    @Test
    @DisplayName("버퍼 잔량이 임계치(2000) 이하일 때는 알림을 보내지 않아야 한다")
    void underThreshold_NoAlert() {
        // given: 잔량 1000개 설정
        testCache.put("UserA", new AtomicLong(1000));

        // when
        monitoringAlertService.checkBufferSaturation();

        // then: 알림 서비스가 한 번도 호출되지 않았는지 확인
        verify(discordAlertService, never()).sendCriticalAlert(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("버퍼 잔량이 임계치(2000)를 초과하면 디스코드 알림을 발송해야 한다")
    void overThreshold_SendAlert() {
        // given: 잔량 2500개 설정 (1500 + 1000)
        testCache.put("UserA", new AtomicLong(1500));
        testCache.put("UserB", new AtomicLong(1000));

        // when
        monitoringAlertService.checkBufferSaturation();

        // then: 알림 서비스가 정확히 1번 호출되었는지 확인
        verify(discordAlertService, times(1)).sendCriticalAlert(
                eq("BUFFER SATURATION WARNING"),
                contains("2500"), // 메시지에 2500이 포함되었는지 확인
                any()
        );
    }
}