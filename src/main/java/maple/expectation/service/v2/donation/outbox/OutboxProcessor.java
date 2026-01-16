package maple.expectation.service.v2.donation.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.domain.v2.DonationOutbox.OutboxStatus;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.DonationOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 처리 서비스 (Issue #80)
 *
 * <h3>Financial-Grade 특성</h3>
 * <ul>
 *   <li>SKIP LOCKED: 분산 환경 중복 처리 방지</li>
 *   <li>Exponential Backoff: 재시도 간격 증가</li>
 *   <li>Triple Safety Net 연동: DLQ → File → Discord</li>
 * </ul>
 *
 * <h3>SOLID 준수 (Blue 리팩토링)</h3>
 * <ul>
 *   <li>SRP: 메트릭 로직을 OutboxMetrics로 분리</li>
 *   <li>DIP: Repository 인터페이스 의존</li>
 * </ul>
 *
 * @see DonationOutboxRepository
 * @see DlqHandler
 * @see OutboxMetrics
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxProcessor {

    private final DonationOutboxRepository outboxRepository;
    private final DlqHandler dlqHandler;
    private final OutboxMetrics metrics;
    private final LogicExecutor executor;

    @Value("${app.instance-id:default-instance}")
    private String instanceId;

    private static final int BATCH_SIZE = 100;
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);

    /**
     * Pending 항목 폴링 및 처리
     *
     * <p>SKIP LOCKED로 분산 환경에서 중복 처리 방지</p>
     */
    @ObservedTransaction("scheduler.outbox.poll")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void pollAndProcess() {
        TaskContext context = TaskContext.of("Outbox", "PollAndProcess", instanceId);

        executor.executeOrCatch(
                () -> {
                    List<DonationOutbox> pending = outboxRepository.findPendingWithLock(
                            List.of(OutboxStatus.PENDING, OutboxStatus.FAILED),
                            LocalDateTime.now(),
                            PageRequest.of(0, BATCH_SIZE)
                    );

                    if (pending.isEmpty()) {
                        return null;
                    }

                    log.info("📤 [Outbox] 처리 시작: {}건", pending.size());
                    processBatch(pending);
                    metrics.updatePendingCount();

                    return null;
                },
                e -> {
                    log.error("❌ [Outbox] 폴링 실패", e);
                    metrics.incrementPollFailure();
                    return null;
                },
                context
        );
    }

    /**
     * 배치 처리 (3-Line Rule 준수)
     */
    private void processBatch(List<DonationOutbox> pending) {
        int success = 0;
        int failed = 0;

        for (DonationOutbox entry : pending) {
            boolean result = processEntry(entry);
            if (result) success++;
            else failed++;
        }

        log.info("📥 [Outbox] 처리 완료: 성공={}, 실패={}", success, failed);
    }

    /**
     * 개별 Outbox 항목 처리
     */
    private boolean processEntry(DonationOutbox entry) {
        TaskContext context = TaskContext.of("Outbox", "ProcessEntry", entry.getRequestId());

        return executor.executeOrDefault(
                () -> {
                    // 무결성 검증 (Content Hash) - Purple 요구사항: 실패 시 즉시 DLQ
                    if (!entry.verifyIntegrity()) {
                        handleIntegrityFailure(entry);
                        return false;
                    }

                    // 처리 시작 마킹
                    entry.markProcessing(instanceId);
                    outboxRepository.save(entry);

                    // 알림 전송 (Best-effort)
                    sendNotification(entry);

                    // 처리 완료 마킹
                    entry.markCompleted();
                    outboxRepository.save(entry);
                    metrics.incrementProcessed();

                    return true;
                },
                false,
                context
        );
    }

    /**
     * 무결성 검증 실패 처리 (Purple 요구사항)
     *
     * <p>재시도 무의미 → 즉시 DEAD_LETTER 이동</p>
     */
    private void handleIntegrityFailure(DonationOutbox entry) {
        log.error("❌ [Outbox] 무결성 검증 실패 → 즉시 DLQ 이동: {}", entry.getRequestId());
        metrics.incrementIntegrityFailure();

        // 즉시 DEAD_LETTER 상태로 변경 (재시도 스킵)
        entry.markFailed("Integrity verification failed - data tampering detected");
        entry.forceDeadLetter();  // 즉시 DLQ 이동
        outboxRepository.save(entry);

        // Triple Safety Net 실행
        dlqHandler.handleDeadLetter(entry, "Integrity verification failed");
    }

    /**
     * 알림 전송 (Best-effort)
     *
     * <p>현재는 로깅만 수행. 실제 Discord 알림은 별도 확장 가능.</p>
     */
    private void sendNotification(DonationOutbox entry) {
        if (!"DONATION_COMPLETED".equals(entry.getEventType())) {
            return;
        }

        log.info("📢 [Outbox] Donation 이벤트 처리 완료: {}", entry.getRequestId());
        metrics.incrementNotificationSent();
    }

    /**
     * 처리 실패 시 호출
     */
    public void handleFailure(DonationOutbox entry, String error) {
        entry.markFailed(error);
        outboxRepository.save(entry);
        metrics.incrementFailed();

        if (entry.shouldMoveToDlq()) {
            dlqHandler.handleDeadLetter(entry, error);
        }
    }

    /**
     * Stalled 상태 복구 (JVM 크래시 대응)
     */
    @ObservedTransaction("scheduler.outbox.recover_stalled")
    @Transactional
    public void recoverStalled() {
        LocalDateTime staleTime = LocalDateTime.now().minus(STALE_THRESHOLD);
        int recovered = outboxRepository.resetStalledProcessing(staleTime);

        if (recovered > 0) {
            log.warn("♻️ [Outbox] Stalled 상태 복구: {}건", recovered);
            metrics.incrementStalledRecovered(recovered);
        }
    }
}
