package maple.expectation.monitoring;

import maple.expectation.global.common.function.ThrowingSupplier;
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
    void leaderSuccess_OverThreshold_SendAlert() throws Throwable {
        given(lockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
                .willAnswer(invocation -> ((ThrowingSupplier<?>) invocation.getArgument(3)).get());

        given(redisBufferRepository.getTotalPendingCount()).willReturn(6000L);

        monitoringAlertService.checkBufferSaturation();

        verify(discordAlertService, times(1)).sendCriticalAlert(anyString(), contains("6000"), any());
    }

    @Test
    @DisplayName("전역 임계치 이하일 때는 리더 권한이 있어도 알림을 보내지 않는다")
    void leaderSuccess_UnderThreshold_NoAlert() throws Throwable {
        given(lockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
                .willAnswer(invocation -> ((ThrowingSupplier<?>) invocation.getArgument(3)).get());

        given(redisBufferRepository.getTotalPendingCount()).willReturn(3000L);

        monitoringAlertService.checkBufferSaturation();

        verify(discordAlertService, never()).sendCriticalAlert(anyString(), anyString(), any());
    }
}