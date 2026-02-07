package maple.expectation.service.v2.donation.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import maple.expectation.config.OutboxProperties;
import maple.expectation.domain.v2.DonationOutbox.OutboxStatus;
import maple.expectation.repository.v2.DonationOutboxRepository;
import org.springframework.stereotype.Component;

/**
 * Outbox 메트릭 관리 (Issue #80)
 *
 * <h3>SRP 준수</h3>
 *
 * <p>OutboxProcessor에서 분리하여 단일 책임 원칙 준수
 *
 * <h3>CLAUDE.md §17 준수</h3>
 *
 * <ul>
 *   <li>소문자 점 표기법
 *   <li>@PostConstruct로 1회만 초기화 (gauge 중복 등록 방지)
 * </ul>
 *
 * @see OutboxProcessor
 */
@Component
@RequiredArgsConstructor
public class OutboxMetrics {

  private final MeterRegistry registry;
  private final DonationOutboxRepository repository;
  private final OutboxProperties properties;

  // Counters (Thread-safe)
  private Counter processedCounter;
  private Counter failedCounter;
  private Counter dlqCounter;
  private Counter dlqReprocessedCounter;
  private Counter dlqDiscardedCounter;
  private Counter fileBackupCounter;
  private Counter criticalCounter;
  private Counter integrityFailureCounter;
  private Counter stalledRecoveredCounter;
  private Counter pollFailureCounter;
  private Counter notificationSentCounter;

  // Gauge backing fields
  private final AtomicLong pendingCount = new AtomicLong(0);
  private final AtomicLong totalCount = new AtomicLong(0);

  /**
   * 메트릭 초기화 (1회만 실행)
   *
   * <p>Green 요구사항: gauge 중복 등록 방지
   */
  @PostConstruct
  public void init() {
    // Counters 초기화
    processedCounter = registry.counter("outbox.processed.total");
    failedCounter = registry.counter("outbox.failed.total");
    dlqCounter = registry.counter("outbox.dlq.total");
    dlqReprocessedCounter = registry.counter("outbox.dlq.reprocessed.total");
    dlqDiscardedCounter = registry.counter("outbox.dlq.discarded.total");
    fileBackupCounter = registry.counter("outbox.safety.file.total");
    criticalCounter = registry.counter("outbox.safety.critical.total");
    integrityFailureCounter = registry.counter("outbox.integrity.failure.total");
    stalledRecoveredCounter = registry.counter("outbox.stalled.recovered.total");
    pollFailureCounter = registry.counter("outbox.poll.failure.total");
    notificationSentCounter = registry.counter("outbox.notification.sent.total");

    // Gauge 초기화 (1회만)
    registry.gauge("outbox.pending.count", pendingCount);
    registry.gauge("outbox.size.total", totalCount);
  }

  // ========== Counter Methods ==========

  public void incrementProcessed() {
    processedCounter.increment();
  }

  public void incrementFailed() {
    failedCounter.increment();
  }

  public void incrementDlq() {
    dlqCounter.increment();
  }

  public void incrementDlqReprocessed() {
    dlqReprocessedCounter.increment();
  }

  public void incrementDlqDiscarded() {
    dlqDiscardedCounter.increment();
  }

  public void incrementFileBackup() {
    fileBackupCounter.increment();
  }

  public void incrementCriticalFailure() {
    criticalCounter.increment();
  }

  public void incrementIntegrityFailure() {
    integrityFailureCounter.increment();
  }

  public void incrementStalledRecovered(int count) {
    stalledRecoveredCounter.increment(count);
  }

  public void incrementPollFailure() {
    pollFailureCounter.increment();
  }

  public void incrementNotificationSent() {
    notificationSentCounter.increment();
  }

  // ========== Gauge Methods ==========

  /**
   * Pending 항목 수 갱신
   *
   * <p>스케줄러에서 주기적으로 호출
   */
  public void updatePendingCount() {
    long count = repository.countByStatusIn(List.of(OutboxStatus.PENDING, OutboxStatus.FAILED));
    pendingCount.set(count);
  }

  /**
   * Outbox 전체 크기 갱신 (모든 상태 포함)
   *
   * <p>Issue #N19: 처리 지연 감지용 메트릭
   *
   * <p>스케줄러에서 주기적으로 호출 (30초)
   */
  public void updateTotalCount() {
    long count = repository.count();
    totalCount.set(count);
  }

  /**
   * Outbox 상태 확인 (백로그 여부)
   *
   * @return true: 백로그 상태 (처리 지연), false: 정상
   */
  public boolean isBacklogged() {
    return totalCount.get() > properties.getSizeAlertThreshold();
  }

  /**
   * 현재 Outbox 크기 조회
   *
   * @return 전체 Outbox 항목 수
   */
  public long getCurrentSize() {
    return totalCount.get();
  }
}
