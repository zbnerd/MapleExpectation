package maple.expectation.lifecycle;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository;
import maple.expectation.infrastructure.shutdown.ShutdownProperties;
import org.springframework.stereotype.Component;

/**
 * Outbox Shutdown 전용 프로세서 (ADR-008)
 *
 * <h3>역할</h3>
 *
 * <ul>
 *   <li>OutboxDrainOnShutdown으로부터 배치 처리 위임
 *   <li>개별 항목 실패 시 다른 항목에 영향 없음 (격리)
 *   <li>메트릭 기록
 * </ul>
 *
 * <h3>CLAUDE.md Section 15 준수</h3>
 *
 * <p>processBatch() 람다가 3줄을 초과하므로 별도 컴포넌트로 추출
 *
 * @see maple.expectation.service.v2.donation.outbox.OutboxProcessor
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxShutdownProcessor {

  private final DonationOutboxRepository outboxRepository;
  private final LogicExecutor executor;
  private final ShutdownProperties properties;

  /**
   * 배치 처리 결과 레코드
   *
   * @param processed 처리 성공 건수
   * @param failed 처리 실패 건수
   */
  public record DrainResult(int processed, int failed) {

    public int processed() {
      return processed;
    }

    public int failed() {
      return failed;
    }
  }

  /**
   * 배치 처리 (Shutdown 전용)
   *
   * <p>각 항목은 독립적으로 처리되며, 실패 시 다른 항목에 영향을 주지 않음
   *
   * @param entries 처리할 Outbox 항목 목록
   * @return 처리 결과 (processed, failed)
   */
  public DrainResult processBatch(List<DonationOutbox> entries) {
    AtomicInteger processed = new AtomicInteger(0);
    AtomicInteger failed = new AtomicInteger(0);

    for (DonationOutbox entry : entries) {
      boolean success =
          executor.executeOrDefault(
              () -> processEntry(entry),
              false,
              TaskContext.of("OutboxShutdown", "ProcessEntry", entry.getRequestId()));

      if (success) {
        processed.incrementAndGet();
      } else {
        failed.incrementAndGet();
      }
    }

    return new DrainResult(processed.get(), failed.get());
  }

  /**
   * 개별 항목 처리
   *
   * <p>Shutdown 시점이므로 재시도 없이 1회만 시도
   *
   * @return 처리 성공 여부
   */
  private boolean processEntry(DonationOutbox entry) {
    if (!entry.verifyIntegrity()) {
      log.warn("[OutboxShutdown] 무결성 검증 실패 -> DEAD_LETTER: {}", entry.getRequestId());
      entry.forceDeadLetter();
      outboxRepository.save(entry);
      return false;
    }

    // 처리 시도
    try {
      entry.markProcessing(properties.getInstanceId());
      entry.markCompleted();
      outboxRepository.save(entry);
      log.info("[OutboxShutdown] 처리 완료: {}", entry.getRequestId());
      return true;
    } catch (Exception e) {
      log.error("[OutboxShutdown] 처리 실패: {}", entry.getRequestId(), e);
      entry.markFailed(e.getMessage());
      outboxRepository.save(entry);

      // DLQ 이동 필요 시
      if (entry.shouldMoveToDlq()) {
        entry.forceDeadLetter();
        outboxRepository.save(entry);
      }
      return false;
    }
  }
}
