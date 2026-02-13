package maple.expectation.monitoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import maple.expectation.alert.StatelessAlertService;
import maple.expectation.config.MonitoringThresholdProperties;
import maple.expectation.domain.repository.RedisBufferRepository;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import maple.expectation.infrastructure.lock.LockStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;

/**
 * MonitoringAlertService 단위 테스트
 *
 * <p>Spring Context 없이 Mockito만으로 핵심 로직을 검증합니다.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MonitoringAlertService 단위 테스트")
class MonitoringAlertServiceUnitTest {

  @Mock private RedisBufferRepository redisBufferRepository;
  @Mock private StatelessAlertService statelessAlertService;
  @Mock private LockStrategy lockStrategy;
  @Mock private MonitoringThresholdProperties thresholdProperties;
  @Mock private LogicExecutor logicExecutor;

  private MonitoringAlertService monitoringAlertService;

  @BeforeEach
  void setUp() {
    // executor.executeOrCatch()가 실제로 람다를 실행하도록 설정
    given(logicExecutor.executeOrCatch(any(), any(), any(TaskContext.class)))
        .willAnswer(
            invocation -> {
              // 첫 번째 인자(ThrowingSupplier)를 실행
              maple.expectation.common.function.ThrowingSupplier<?> task =
                  invocation.getArgument(0);
              return task.get();
            });

    // executor.executeVoid()가 실제로 람다를 실행하도록 설정 (void 메서드)
    org.mockito.Mockito.doAnswer(
            invocation -> {
              ThrowingRunnable task = invocation.getArgument(0);
              task.run();
              return null;
            })
        .when(logicExecutor)
        .executeVoid(any(), any(TaskContext.class));

    monitoringAlertService =
        new MonitoringAlertService(
            redisBufferRepository,
            statelessAlertService,
            lockStrategy,
            logicExecutor,
            thresholdProperties);
  }

  @Test
  @DisplayName("리더 권한을 획득하고 전역 임계치를 초과하면 알림을 발송한다")
  void leaderSuccess_OverThreshold_SendAlert() {
    // given
    given(lockStrategy.tryLockImmediately(eq("global-monitoring-lock"), eq(4L))).willReturn(true);
    given(redisBufferRepository.getTotalPendingCount()).willReturn(6000L);
    given(thresholdProperties.bufferSaturationCount()).willReturn(5000L);

    // when
    monitoringAlertService.checkBufferSaturation();

    // then
    verify(statelessAlertService, times(1)).sendCritical(any(), any(), any());
  }

  @Test
  @DisplayName("전역 임계치 이하일 때는 리더 권한이 있어도 알림을 보내지 않는다")
  void leaderSuccess_UnderThreshold_NoAlert() {
    // given
    given(lockStrategy.tryLockImmediately(eq("global-monitoring-lock"), eq(4L))).willReturn(true);
    given(redisBufferRepository.getTotalPendingCount()).willReturn(3000L);
    given(thresholdProperties.bufferSaturationCount()).willReturn(5000L);

    // when
    monitoringAlertService.checkBufferSaturation();

    // then
    verify(statelessAlertService, never()).sendCritical(any(), any(), any());
  }

  @Test
  @DisplayName("리더 선출 실패 시 모니터링을 스킵한다")
  void follower_SkipMonitoring() {
    // given
    given(lockStrategy.tryLockImmediately(eq("global-monitoring-lock"), eq(4L))).willReturn(false);

    // when
    monitoringAlertService.checkBufferSaturation();

    // then
    // Follower는 버퍼 조회 및 알림 발송을 하지 않아야 함
    verify(redisBufferRepository, never()).getTotalPendingCount();
    verify(statelessAlertService, never()).sendCritical(any(), any(), any());
  }

  @Test
  @DisplayName("정확히 임계값(5000)을 초과하면 알림을 발송한다")
  void exactlyAtThreshold_SendAlert() {
    // given
    given(lockStrategy.tryLockImmediately(eq("global-monitoring-lock"), eq(4L))).willReturn(true);
    given(redisBufferRepository.getTotalPendingCount()).willReturn(5001L);
    given(thresholdProperties.bufferSaturationCount()).willReturn(5000L);

    // when
    monitoringAlertService.checkBufferSaturation();

    // then
    verify(statelessAlertService, times(1)).sendCritical(any(), any(), any());
  }

  @Test
  @DisplayName("버퍼가 비어있을 때는 알림을 보내지 않는다")
  void bufferZero_NoAlert() {
    // given
    given(lockStrategy.tryLockImmediately(eq("global-monitoring-lock"), eq(4L))).willReturn(true);
    given(redisBufferRepository.getTotalPendingCount()).willReturn(0L);
    given(thresholdProperties.bufferSaturationCount()).willReturn(5000L);

    // when
    monitoringAlertService.checkBufferSaturation();

    // then
    verify(statelessAlertService, never()).sendCritical(any(), any(), any());
  }

  @Test
  @DisplayName("복구 가능한 분산 락 획득 실패 시 재시도하지 않고 스킵한다")
  void lockAcquisitionFailure_SkipMonitoring() {
    // given
    given(lockStrategy.tryLockImmediately(eq("global-monitoring-lock"), eq(4L))).willReturn(false);

    // when
    monitoringAlertService.checkBufferSaturation();

    // then
    verify(redisBufferRepository, never()).getTotalPendingCount();
    verify(statelessAlertService, never()).sendCritical(any(), any(), any());
  }
}
