package maple.expectation.service.v2.donation.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import maple.expectation.domain.v2.DonationOutbox.OutboxStatus;
import maple.expectation.repository.v2.DonationOutboxRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 메트릭 관리 (Issue #80)
 *
 * <h3>SRP 준수</h3>
 * <p>OutboxProcessor에서 분리하여 단일 책임 원칙 준수</p>
 *
 * <h3>CLAUDE.md §17 준수</h3>
 * <ul>
 *   <li>소문자 점 표기법</li>
 *   <li>@PostConstruct로 1회만 초기화 (gauge 중복 등록 방지)</li>
 * </ul>
 *
 * @see OutboxProcessor
 */
@Component
@RequiredArgsConstructor
public class OutboxMetrics {

    private final MeterRegistry registry;
    private final DonationOutboxRepository repository;

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

    // Gauge backing field
    private final AtomicLong pendingCount = new AtomicLong(0);

    /**
     * 메트릭 초기화 (1회만 실행)
     *
     * <p>Green 요구사항: gauge 중복 등록 방지</p>
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
        for (int i = 0; i < count; i++) {
            stalledRecoveredCounter.increment();
        }
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
     * <p>스케줄러에서 주기적으로 호출</p>
     */
    public void updatePendingCount() {
        long count = repository.countByStatusIn(
                List.of(OutboxStatus.PENDING, OutboxStatus.FAILED)
        );
        pendingCount.set(count);
    }
}
