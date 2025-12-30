package maple.expectation.monitoring;

import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.repository.v2.RedisBufferRepository; // ✅ 추가
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@SpringBootTest
class MonitoringAlertServiceTest {

    @Autowired
    private MonitoringAlertService monitoringAlertService;

    @MockitoBean
    private RedisBufferRepository redisBufferRepository; // ✅ 이제 로컬 버퍼 대신 Redis 리포지토리를 Mocking

    @MockitoBean
    private DiscordAlertService discordAlertService;

    @MockitoBean
    private LockStrategy lockStrategy;

    @Test
    @DisplayName("리더 권한을 획득하고 전역 임계치(5000)를 초과하면 알림을 발송한다")
    void leaderSuccess_OverThreshold_SendAlert() throws Throwable {
        // 1. Given: 락 획득 성공 시뮬레이션
        given(lockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
                .willAnswer(invocation -> {
                    ThrowingSupplier<?> supplier = invocation.getArgument(3);
                    return supplier.get();
                });

        // 💡 핵심: Redis 전역 카운트가 5000을 넘도록 설정 (6000 반환)
        given(redisBufferRepository.getTotalPendingCount()).willReturn(6000L);

        // 2. When
        monitoringAlertService.checkBufferSaturation();

        // 3. Then: 알림이 1번 호출되어야 함
        verify(discordAlertService, times(1)).sendCriticalAlert(
                contains("GLOBAL"), // 제목에 GLOBAL 포함 확인
                contains("6000"),   // 메시지에 현재 수치 포함 확인
                any()
        );
    }

    @Test
    @DisplayName("전역 임계치(5000) 이하일 때는 리더 권한이 있어도 알림을 보내지 않는다")
    void leaderSuccess_UnderThreshold_NoAlert() throws Throwable {
        // given
        given(lockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
                .willAnswer(invocation -> ((ThrowingSupplier<?>) invocation.getArgument(3)).get());

        // 💡 임계치 미만인 3000 설정
        given(redisBufferRepository.getTotalPendingCount()).willReturn(3000L);

        // when
        monitoringAlertService.checkBufferSaturation();

        // then
        verify(discordAlertService, never()).sendCriticalAlert(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("리더 권한 획득에 실패하면 수치와 상관없이 알림을 보내지 않는다")
    void leaderFail_NoAlert() throws Throwable {
        // given: 락 획득 실패
        given(lockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
                .willThrow(new RuntimeException("Lock failed"));

        given(redisBufferRepository.getTotalPendingCount()).willReturn(6000L);

        // when
        monitoringAlertService.checkBufferSaturation();

        // then
        verify(discordAlertService, never()).sendCriticalAlert(anyString(), anyString(), any());
    }
}