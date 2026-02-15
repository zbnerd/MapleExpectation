package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.service.v2.outbox.NexonApiOutboxMetrics;
import maple.expectation.service.v2.outbox.NexonApiOutboxProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nexon API Outbox 폴링 스케줄러 (N19)
 *
 * <h3>스케줄링 주기</h3>
 *
 * <ul>
 *   <li>pollAndProcess: 10초 (Pending -> Processing -> Completed)
 *   <li>recoverStalled: 5분 (JVM 크래시 대응)
 * </ul>
 *
 * <h3>분산 환경 안전</h3>
 *
 * <p><b>분산 락 미적용 사유</b>: {@link
 * maple.expectation.infrastructure.persistence.repository.NexonApiOutboxRepository}의 {@code
 * findPendingWithLock()} 및 {@code findStalledProcessing()} 메서드는 {@code PESSIMISTIC_WRITE} + {@code
 * SKIP LOCKED} 쿼리를 사용하여 DB 레벨에서 중복 처리를 방지합니다.
 *
 * <p><b>SKIP LOCKED 동작 원리</b>:
 *
 * <ul>
 *   <li>다중 인스턴스가 동시에 폴링 시, 잠긴 행은 건너뛰고 다음 행 처리
 *   <li>대기 없이 병렬 처리 가능 (높은 처리량 보장)
 *   <li>Redis 분산 락 대비 장점: Redis 장애 시에도 독립 동작
 * </ul>
 *
 * <h3>메트릭 수집</h3>
 *
 * <ul>
 *   <li>updatePendingCount(): 배치 처리 전후로 Pending 수 갱신
 *   <li>처리 결과 로깅: 성공/실패 건수 기록
 * </ul>
 *
 * @see NexonApiOutboxProcessor
 * @see
 *     maple.expectation.infrastructure.persistence.repository.NexonApiOutboxRepository#findPendingWithLock
 * @see
 *     maple.expectation.infrastructure.persistence.repository.NexonApiOutboxRepository#findStalledProcessing
 * @see <a
 *     href="../../../../docs/01_Chaos_Engineering/06_Nightmare/Scenarios/N19-outbox-replay.md">N19
 *     Scenario</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "scheduler.nexon-api-outbox.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class NexonApiOutboxScheduler {

  private final NexonApiOutboxProcessor outboxProcessor;
  private final NexonApiOutboxMetrics outboxMetrics;
  private final LogicExecutor executor;

  /**
   * Nexon API Outbox 폴링 및 처리 (10초)
   *
   * <h3>처리 흐름</h3>
   *
   * <ol>
   *   <li>메트릭 갱신: 처리 전 Pending 수 기록
   *   <li>폴링 및 처리: processor.pollAndProcess() 호출
   *   <li>메트릭 갱신: 처리 후 Pending 수 기록
   * </ol>
   *
   * <p>Issue #344: fixedRate → fixedDelay to prevent scheduler overlap
   *
   * <p>Note: fixedDelay ensures no overlap even if processing takes longer than interval
   */
  @Scheduled(fixedDelay = 10000)
  public void pollAndProcess() {
    executor.executeVoid(
        () -> {
          // 메트릭: 처리 전 Pending 수
          outboxMetrics.updatePendingCount();

          // Outbox 폴링 및 처리
          outboxProcessor.pollAndProcess();

          // 메트릭: 처리 후 Pending 수
          outboxMetrics.updatePendingCount();
        },
        TaskContext.of("Scheduler", "NexonApiOutbox.Poll"));
  }

  /**
   * Stalled 상태 복구 (5분)
   *
   * <p>JVM 크래시로 PROCESSING 상태에서 멈춘 항목을 PENDING으로 복원
   *
   * <h3>복구 대상</h3>
   *
   * <ul>
   *   <li>status = PROCESSING
   *   <li>lockedAt < (now - 5분)
   * </ul>
   *
   * <p>Issue #344: fixedRate → fixedDelay to prevent scheduler overlap
   *
   * <p>Note: fixedDelay ensures no overlap even if processing takes longer than interval
   */
  @Scheduled(fixedDelay = 300000)
  public void recoverStalled() {
    executor.executeVoid(
        outboxProcessor::recoverStalled,
        TaskContext.of("Scheduler", "NexonApiOutbox.RecoverStalled"));
  }
}
