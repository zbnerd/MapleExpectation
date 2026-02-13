package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.config.OutboxProperties;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.service.v2.donation.outbox.OutboxMetrics;
import maple.expectation.service.v2.donation.outbox.OutboxProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 폴링 스케줄러 (Issue #80)
 *
 * <h3>스케줄링 주기</h3>
 *
 * <ul>
 *   <li>pollAndProcess: 10초 (Pending -> Processing -> Completed)
 *   <li>recoverStalled: 5분 (JVM 크래시 대응)
 * </ul>
 *
 * <h3>P1-7 Fix: updatePendingCount() 스케줄러 레벨로 이동</h3>
 *
 * <p>기존: OutboxProcessor.pollAndProcess() 내부에서 호출 (배치 트랜잭션 내 추가 쿼리)
 *
 * <p>수정: 스케줄러에서 독립적으로 호출 (배치 처리 시간 단축)
 *
 * <h3>분산 환경 안전 (Issue #283 P1-9)</h3>
 *
 * <p><b>분산 락 미적용 사유</b>: {@link DonationOutboxRepository}의 {@code findPendingWithLock()} 및 {@code
 * findStalledProcessing()} 메서드는 {@code PESSIMISTIC_WRITE} + {@code SKIP LOCKED} 쿼리를 사용하여 DB 레벨에서 중복
 * 처리를 방지합니다.
 *
 * <p><b>SKIP LOCKED 동작 원리</b>:
 *
 * <ul>
 *   <li>다중 인스턴스가 동시에 폴링 시, 잠긴 행은 건너뛰고 다음 행 처리
 *   <li>대기 없이 병렬 처리 가능 (높은 처리량 보장)
 *   <li>Redis 분산 락 대비 장점: Redis 장애 시에도 독립 동작
 * </ul>
 *
 * @see OutboxProcessor
 * @see DonationOutboxRepository#findPendingWithLock
 * @see DonationOutboxRepository#findStalledProcessing
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "scheduler.outbox.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxScheduler {

  private final OutboxProcessor outboxProcessor;
  private final OutboxMetrics outboxMetrics;
  private final LogicExecutor executor;
  private final OutboxProperties properties;

  /**
   * Outbox 폴링 및 처리 (10초)
   *
   * <h4>Issue #344: fixedRate → fixedDelay</h4>
   *
   * <p>이전 실행 완료 후 15초 대기, Outbox 폴링 여유 확보로 DB 부하 감소
   */
  @Scheduled(fixedDelay = 15000)
  public void pollAndProcess() {
    executor.executeVoid(
        () -> {
          outboxProcessor.pollAndProcess();
          outboxMetrics.updatePendingCount();
        },
        TaskContext.of("Scheduler", "Outbox.Poll"));
  }

  /**
   * Outbox 크기 모니터링 (30초)
   *
   * <p>Issue #N19: 처리 지연 감지
   *
   * <p>Outbox 크기가 임계값 초과 시 로그 기록
   *
   * <h4>Issue #344: fixedRate → fixedDelay</h4>
   *
   * <p>이전 실행 완료 후 60초 대기, 모니터링은 덜 빈번해도 충분
   */
  @Scheduled(fixedDelay = 60000)
  public void monitorOutboxSize() {
    executor.executeVoid(
        () -> {
          outboxMetrics.updateTotalCount();
          long currentSize = outboxMetrics.getCurrentSize();
          int threshold = properties.getSizeAlertThreshold();

          if (currentSize > threshold) {
            log.warn("[Outbox] 백로그 감지: {}건 (임계값: {}건)", currentSize, threshold);
          }
        },
        TaskContext.of("Scheduler", "Outbox.MonitorSize"));
  }

  /**
   * Stalled 상태 복구 (5분)
   *
   * <p>JVM 크래시로 PROCESSING 상태에서 멈춘 항목을 PENDING으로 복원
   *
   * <h4>Issue #344: fixedRate → fixedDelay</h4>
   *
   * <p>이전 실행 완료 후 5분 대기 (변경 없음, 복구 작업은 빈번하지 않아도 됨)
   */
  @Scheduled(fixedDelay = 300000)
  public void recoverStalled() {
    executor.executeVoid(
        outboxProcessor::recoverStalled, TaskContext.of("Scheduler", "Outbox.RecoverStalled"));
  }
}
