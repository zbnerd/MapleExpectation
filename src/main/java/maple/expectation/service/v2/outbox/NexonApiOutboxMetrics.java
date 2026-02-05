package maple.expectation.service.v2.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import maple.expectation.domain.v2.NexonApiOutbox.OutboxStatus;
import maple.expectation.repository.v2.NexonApiOutboxRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nexon API Outbox 메트릭 관리 (N19)
 *
 * <h3>SRP 준수</h3>
 * <p>NexonApiOutboxProcessor에서 분리하여 단일 책임 원칙 준수</p>
 *
 * <h3>CLAUDE.md §17 준수</h3>
 * <ul>
 *   <li>소문자 점 표기법 (nexon_api_outbox.*)</li>
 *   <li>@PostConstruct로 1회만 초기화 (gauge 중복 등록 방지)</li>
 * </ul>
 *
 * @see maple.expectation.service.v2.outbox.NexonApiOutboxProcessor
 */
@Component
@RequiredArgsConstructor
public class NexonApiOutboxMetrics {

    private final MeterRegistry registry;
    private final NexonApiOutboxRepository repository;

    // Counters (Thread-safe)
    private Counter processedCounter;
    private Counter failedCounter;
    private Counter dlqCounter;
    private Counter integrityFailureCounter;
    private Counter stalledRecoveredCounter;
    private Counter pollFailureCounter;
    private Counter apiCallSuccessCounter;
    private Counter apiCallRetryCounter;

    // Gauge backing field
    private final AtomicLong pendingCount = new AtomicLong(0);

    /**
     * 메트릭 초기화 (1회만 실행)
     *
     * <p>gauge 중복 등록 방지</p>
     */
    @PostConstruct
    public void init() {
        // Counters 초기화
        processedCounter = registry.counter("nexon_api_outbox.processed.total");
        failedCounter = registry.counter("nexon_api_outbox.failed.total");
        dlqCounter = registry.counter("nexon_api_outbox.dlq.total");
        integrityFailureCounter = registry.counter("nexon_api_outbox.integrity.failure.total");
        stalledRecoveredCounter = registry.counter("nexon_api_outbox.stalled.recovered.total");
        pollFailureCounter = registry.counter("nexon_api_outbox.poll.failure.total");
        apiCallSuccessCounter = registry.counter("nexon_api_outbox.api_call.success.total");
        apiCallRetryCounter = registry.counter("nexon_api_outbox.api_call.retry.total");

        // Gauge 초기화 (1회만)
        registry.gauge("nexon_api_outbox.pending.count", pendingCount);
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

    public void incrementIntegrityFailure() {
        integrityFailureCounter.increment();
    }

    public void incrementStalledRecovered(int count) {
        stalledRecoveredCounter.increment(count);
    }

    public void incrementPollFailure() {
        pollFailureCounter.increment();
    }

    public void incrementApiCallSuccess() {
        apiCallSuccessCounter.increment();
    }

    public void incrementApiCallRetry() {
        apiCallRetryCounter.increment();
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
