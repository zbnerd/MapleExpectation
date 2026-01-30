package maple.expectation.service.v2.donation.outbox;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.config.OutboxProperties;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.domain.v2.DonationOutbox.OutboxStatus;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.DonationOutboxRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 처리 서비스 (Issue #80)
 *
 * <h3>Financial-Grade 특성</h3>
 * <ul>
 *   <li>SKIP LOCKED: 분산 환경 중복 처리 방지</li>
 *   <li>Exponential Backoff: 재시도 간격 증가</li>
 *   <li>Triple Safety Net 연동: DLQ -> File -> Discord</li>
 * </ul>
 *
 * <h3>P0 리팩토링</h3>
 * <ul>
 *   <li>P0-1: processEntry() Zombie Loop 수정 — 실패 시 반드시 handleFailure() 호출</li>
 *   <li>P0-2: 단일 트랜잭션 배치 -> 항목별 독립 트랜잭션 (TransactionTemplate)</li>
 * </ul>
 *
 * <h3>P1 리팩토링</h3>
 * <ul>
 *   <li>P1-2: instanceId @Value -> OutboxProperties 생성자 주입</li>
 *   <li>P1-7: updatePendingCount()를 스케줄러 레벨로 이동</li>
 *   <li>P1-8: BATCH_SIZE, STALE_THRESHOLD -> OutboxProperties 외부화</li>
 * </ul>
 *
 * @see DonationOutboxRepository
 * @see DlqHandler
 * @see OutboxMetrics
 */
@Slf4j
@Service
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxProcessor {

    private final DonationOutboxRepository outboxRepository;
    private final DlqHandler dlqHandler;
    private final OutboxMetrics metrics;
    private final LogicExecutor executor;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties properties;

    public OutboxProcessor(DonationOutboxRepository outboxRepository,
                           DlqHandler dlqHandler,
                           OutboxMetrics metrics,
                           LogicExecutor executor,
                           TransactionTemplate transactionTemplate,
                           OutboxProperties properties) {
        this.outboxRepository = outboxRepository;
        this.dlqHandler = dlqHandler;
        this.metrics = metrics;
        this.executor = executor;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    /**
     * Pending 항목 폴링 및 처리
     *
     * <h4>P0-2 Fix: 2-Phase 처리</h4>
     * <ol>
     *   <li>Phase 1 (TX): SKIP LOCKED 조회 + markProcessing + save</li>
     *   <li>Phase 2 (항목별 TX): 개별 처리 (실패 시 다른 항목에 영향 없음)</li>
     * </ol>
     */
    @ObservedTransaction("scheduler.outbox.poll")
    public void pollAndProcess() {
        TaskContext context = TaskContext.of("Outbox", "PollAndProcess", properties.getInstanceId());

        executor.executeOrCatch(
                () -> {
                    List<DonationOutbox> locked = fetchAndLock();
                    if (locked.isEmpty()) {
                        return null;
                    }

                    log.info("[Outbox] 처리 시작: {}건", locked.size());
                    processBatch(locked);
                    return null;
                },
                e -> {
                    log.error("[Outbox] 폴링 실패", e);
                    metrics.incrementPollFailure();
                    return null;
                },
                context
        );
    }

    /**
     * Phase 1: SKIP LOCKED 조회 + markProcessing (단일 트랜잭션)
     *
     * <p>트랜잭션 종료와 함께 SKIP LOCKED 해제되지만,
     * 상태가 PROCESSING으로 변경되어 다른 인스턴스가 재조회하지 않음</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<DonationOutbox> fetchAndLock() {
        List<DonationOutbox> pending = outboxRepository.findPendingWithLock(
                List.of(OutboxStatus.PENDING, OutboxStatus.FAILED),
                LocalDateTime.now(),
                PageRequest.of(0, properties.getBatchSize())
        );

        for (DonationOutbox entry : pending) {
            entry.markProcessing(properties.getInstanceId());
        }

        return outboxRepository.saveAll(pending);
    }

    /**
     * Phase 2: 배치 처리 (항목별 독립 트랜잭션)
     *
     * <p>P0-2 Fix: 개별 항목 실패가 전체 배치에 영향을 주지 않음</p>
     */
    private void processBatch(List<DonationOutbox> locked) {
        int success = 0;
        int failed = 0;

        for (DonationOutbox entry : locked) {
            boolean result = processEntryInTransaction(entry.getId());
            if (result) success++;
            else failed++;
        }

        log.info("[Outbox] 처리 완료: 성공={}, 실패={}", success, failed);
    }

    /**
     * 개별 Outbox 항목 처리 (독립 트랜잭션)
     *
     * <h4>P0-1 Fix: Zombie Loop 방지</h4>
     * <p>executeOrCatch로 실패 시 반드시 handleFailure() 호출 -> retryCount 증가 -> DLQ 이동</p>
     */
    private boolean processEntryInTransaction(Long entryId) {
        TaskContext context = TaskContext.of("Outbox", "ProcessEntry", String.valueOf(entryId));

        return executor.executeOrCatch(
                () -> {
                    Boolean result = transactionTemplate.execute(status -> {
                        DonationOutbox entry = outboxRepository.findById(entryId).orElse(null);
                        if (entry == null) {
                            return false;
                        }

                        return processEntry(entry);
                    });
                    return Boolean.TRUE.equals(result);
                },
                e -> {
                    log.error("[Outbox] 항목 처리 실패: id={}", entryId, e);
                    recoverFailedEntry(entryId, e.getMessage());
                    return false;
                },
                context
        );
    }

    /**
     * 개별 항목 처리 로직 (트랜잭션 내부)
     */
    private boolean processEntry(DonationOutbox entry) {
        if (!entry.verifyIntegrity()) {
            handleIntegrityFailure(entry);
            return false;
        }

        sendNotification(entry);
        entry.markCompleted();
        outboxRepository.save(entry);
        metrics.incrementProcessed();
        return true;
    }

    /**
     * P0-1 Fix: 실패 항목 복구 (별도 트랜잭션)
     *
     * <p>processEntry 예외 발생 시 반드시 retryCount를 증가시켜 Zombie Loop 방지</p>
     */
    private void recoverFailedEntry(Long entryId, String errorMessage) {
        TaskContext context = TaskContext.of("Outbox", "RecoverFailed", String.valueOf(entryId));

        executor.executeOrDefault(
                () -> {
                    transactionTemplate.executeWithoutResult(status -> {
                        DonationOutbox entry = outboxRepository.findById(entryId).orElse(null);
                        if (entry == null) {
                            return;
                        }
                        handleFailure(entry, errorMessage);
                    });
                    return null;
                },
                null,
                context
        );
    }

    /**
     * 무결성 검증 실패 처리 (Purple 요구사항)
     *
     * <p>재시도 무의미 -> 즉시 DEAD_LETTER 이동</p>
     */
    private void handleIntegrityFailure(DonationOutbox entry) {
        log.error("[Outbox] 무결성 검증 실패 -> 즉시 DLQ 이동: {}", entry.getRequestId());
        metrics.incrementIntegrityFailure();

        entry.markFailed("Integrity verification failed - data tampering detected");
        entry.forceDeadLetter();
        outboxRepository.save(entry);

        dlqHandler.handleDeadLetter(entry, "Integrity verification failed");
    }

    /**
     * 알림 전송 (Best-effort)
     */
    private void sendNotification(DonationOutbox entry) {
        if (!"DONATION_COMPLETED".equals(entry.getEventType())) {
            return;
        }

        log.info("[Outbox] Donation 이벤트 처리 완료: {}", entry.getRequestId());
        metrics.incrementNotificationSent();
    }

    /**
     * 처리 실패 핸들링
     *
     * <p>P0-1 Fix: 반드시 retryCount 증가 -> maxRetries 도달 시 DLQ 이동</p>
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
     *
     * <p>Purple Agent 요구사항: 복구 전 Content Hash 기반 무결성 검증</p>
     */
    @ObservedTransaction("scheduler.outbox.recover_stalled")
    @Transactional
    public void recoverStalled() {
        LocalDateTime staleTime = LocalDateTime.now().minus(properties.getStaleThreshold());
        List<DonationOutbox> stalledEntries = outboxRepository.findStalledProcessing(
                staleTime,
                PageRequest.of(0, properties.getBatchSize())
        );

        if (stalledEntries.isEmpty()) {
            return;
        }

        log.info("[Outbox] Stalled 상태 발견: {}건, 무결성 검증 시작", stalledEntries.size());

        int recovered = 0;
        int integrityFailed = 0;

        for (DonationOutbox entry : stalledEntries) {
            if (!entry.verifyIntegrity()) {
                log.error("[Outbox] 무결성 검증 실패 - Zombie 복구 중단, DLQ 이동: requestId={}",
                        entry.getRequestId());
                handleIntegrityFailure(entry);
                integrityFailed++;
                continue;
            }

            entry.resetToRetry();
            outboxRepository.save(entry);
            recovered++;
        }

        if (recovered > 0) {
            log.warn("[Outbox] Stalled 상태 복구 완료: 성공={}, 무결성실패={}", recovered, integrityFailed);
            metrics.incrementStalledRecovered(recovered);
        }

        if (integrityFailed > 0) {
            log.error("[Outbox] Stalled 복구 중 무결성 검증 실패: {}건", integrityFailed);
        }
    }
}
