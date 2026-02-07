package maple.expectation.monitoring;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.repository.RedisBufferRepository;
import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.MonitoringException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MonitoringAlertService {

  private final RedisBufferRepository redisBufferRepository;
  private final DiscordAlertService discordAlertService;
  private final LockStrategy lockStrategy;
  private final LogicExecutor executor; // ✅ 지능형 실행 엔진 주입

  /**
   * ✅ 버퍼 포화도 체크 로직 (Leader Election 패턴)
   *
   * <p>tryLockImmediately()를 사용하여 예외 없이 리더 선출. 락 획득 실패(Follower)는 정상 시나리오이므로 조용히 스킵.
   */
  @Scheduled(fixedRate = 5000)
  public void checkBufferSaturation() {
    TaskContext context = TaskContext.of("Monitoring", "CheckSaturation");

    // Leader Election: 락 획득 성공한 인스턴스만 모니터링 수행
    boolean isLeader = lockStrategy.tryLockImmediately("global-monitoring-lock", 4);

    if (!isLeader) {
      log.debug("⏭️ [Monitoring] 리더 선출 실패 - 다른 인스턴스가 리더입니다. 체크 스킵.");
      return;
    }

    // Leader로 선출됨 → 모니터링 수행 (에러 시 로깅 후 스케줄러 계속 동작)
    executor.executeOrCatch(
        () -> {
          performBufferCheck();
          return null;
        },
        this::handleMonitoringFailure,
        context);
  }

  /** 헬퍼 1: 실제 수치 확인 및 알림 로직 (로직 응집도 향상) */
  private void performBufferCheck() {
    long globalPending = redisBufferRepository.getTotalPendingCount();

    if (globalPending > 5000) {
      MonitoringException exception =
          new MonitoringException(CommonErrorCode.SYSTEM_CAPACITY_EXCEEDED, globalPending);

      // [패턴 1] executeVoid: 외부 알림 발송 과정도 실행기로 보호하여 관측성 확보
      executor.executeVoid(
          () -> {
            discordAlertService.sendCriticalAlert(
                "🚨 GLOBAL BUFFER SATURATION", exception.getMessage(), exception);
            log.warn("[{}] {}", exception.getErrorCode().getCode(), exception.getMessage());
          },
          TaskContext.of("Alert", "SendDiscord", String.valueOf(globalPending)));
    }
  }

  /** 모니터링 실패 대응 (실제 장애만 로깅) */
  private Void handleMonitoringFailure(Throwable t) {
    log.error("❌ [Monitoring] 버퍼 모니터링 중 장애 발생: {}", t.getMessage(), t);
    return null;
  }
}
