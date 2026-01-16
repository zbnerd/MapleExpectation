package maple.expectation.monitoring;

import maple.expectation.global.lock.LockStrategy;
import maple.expectation.repository.v2.RedisBufferRepository;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

class MonitoringAlertServiceTest extends IntegrationTestSupport {

    // 💡 실제 MonitoringAlertService를 테스트하기 위해 필요한 의존성들을 Mock으로 오버라이드
    @MockitoBean
    private LockStrategy lockStrategy;

    @MockitoBean
    private RedisBufferRepository redisBufferRepository;

    @Autowired
    private MonitoringAlertService monitoringAlertService;

    @Test
    @DisplayName("리더 권한을 획득하고 전역 임계치를 초과하면 알림을 발송한다")
    void leaderSuccess_OverThreshold_SendAlert() {
        // Leader Election: tryLockImmediately()가 true 반환 → 리더 획득
        given(lockStrategy.tryLockImmediately(eq("global-monitoring-lock"), eq(4L)))
                .willReturn(true);

        given(redisBufferRepository.getTotalPendingCount()).willReturn(6000L);

        monitoringAlertService.checkBufferSaturation();

        verify(discordAlertService, times(1)).sendCriticalAlert(anyString(), contains("6000"), any());
    }

    @Test
    @DisplayName("전역 임계치 이하일 때는 리더 권한이 있어도 알림을 보내지 않는다")
    void leaderSuccess_UnderThreshold_NoAlert() {
        // Leader Election: tryLockImmediately()가 true 반환 → 리더 획득
        given(lockStrategy.tryLockImmediately(eq("global-monitoring-lock"), eq(4L)))
                .willReturn(true);

        given(redisBufferRepository.getTotalPendingCount()).willReturn(3000L);

        monitoringAlertService.checkBufferSaturation();

        verify(discordAlertService, never()).sendCriticalAlert(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("리더 선출 실패 시 모니터링을 스킵한다")
    void follower_SkipMonitoring() {
        // Leader Election: tryLockImmediately()가 false 반환 → Follower
        given(lockStrategy.tryLockImmediately(eq("global-monitoring-lock"), eq(4L)))
                .willReturn(false);

        monitoringAlertService.checkBufferSaturation();

        // Follower는 버퍼 조회 및 알림 발송을 하지 않아야 함
        verify(redisBufferRepository, never()).getTotalPendingCount();
        verify(discordAlertService, never()).sendCriticalAlert(anyString(), anyString(), any());
    }
}