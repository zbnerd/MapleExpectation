package maple.expectation.monitoring;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import maple.expectation.domain.repository.RedisBufferRepository;
import maple.expectation.infrastructure.lock.LockStrategy;
import maple.expectation.support.AppIntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Monitoring Alert Service 테스트
 *
 * <p>리더 선출 및 버퍼 포화도 모니터링 로직을 검증합니다.
 *
 * <h4>Performance Optimization (SharedContainers)</h4>
 *
 * <ul>
 *   <li>Uses JVM-wide singleton MySQL/Redis containers
 *   <li>Mock dependencies for faster test execution
 *   <li>~60-80% faster than per-test container startup
 * </ul>
 *
 * @see maple.expectation.support.SharedContainers
 */
@TestPropertySource(
    properties = {
      // Disable batch jobs for tests
      "spring.batch.job.enabled=false",
    })
@DisplayName("Monitoring Alert Service 테스트")
class MonitoringAlertServiceTest extends AppIntegrationTestSupport {

  // 💡 실제 MonitoringAlertService를 테스트하기 위해 필요한 의존성들을 Mock으로 오버라이드
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private LockStrategy lockStrategy;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private RedisBufferRepository redisBufferRepository;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private maple.expectation.alert.StatelessAlertService statelessAlertService;

  @Autowired private MonitoringAlertService monitoringAlertService;

  @Test
  @DisplayName("리더 권한을 획득하고 전역 임계치를 초과하면 알림을 발송한다")
  void leaderSuccess_OverThreshold_SendAlert() {
    // Leader Election: tryLockImmediately()가 true 반환 → 리더 획득
    given(lockStrategy.tryLockImmediately(eq("global-monitoring-lock"), eq(4L))).willReturn(true);

    given(redisBufferRepository.getTotalPendingCount()).willReturn(6000L);

    monitoringAlertService.checkBufferSaturation();

    verify(statelessAlertService, times(1))
        .sendCritical(eq("🚨 GLOBAL BUFFER SATURATION"), contains("6000"), any());
  }

  @Test
  @DisplayName("전역 임계치 이하일 때는 리더 권한이 있어도 알림을 보내지 않는다")
  void leaderSuccess_UnderThreshold_NoAlert() {
    // Leader Election: tryLockImmediately()가 true 반환 → 리더 획득
    given(lockStrategy.tryLockImmediately(eq("global-monitoring-lock"), eq(4L))).willReturn(true);

    given(redisBufferRepository.getTotalPendingCount()).willReturn(3000L);

    monitoringAlertService.checkBufferSaturation();

    verify(statelessAlertService, never()).sendCritical(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("리더 선출 실패 시 모니터링을 스킵한다")
  void follower_SkipMonitoring() {
    // Leader Election: tryLockImmediately()가 false 반환 → Follower
    given(lockStrategy.tryLockImmediately(eq("global-monitoring-lock"), eq(4L))).willReturn(false);

    monitoringAlertService.checkBufferSaturation();

    // Follower는 버퍼 조회 및 알림 발송을 하지 않아야 함
    verify(redisBufferRepository, never()).getTotalPendingCount();
    verify(statelessAlertService, never()).sendCritical(anyString(), anyString(), any());
  }
}
