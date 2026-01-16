package maple.expectation.service.v2.donation.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.DonationDlq;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.DonationDlqRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.springframework.stereotype.Service;

/**
 * Dead Letter Queue 처리 서비스 (Issue #80)
 *
 * <h3>Triple Safety Net (P0 - 데이터 영구 손실 방지)</h3>
 * <ol>
 *   <li><b>1차</b>: DB DLQ INSERT</li>
 *   <li><b>2차</b>: File Backup (DLQ 실패 시)</li>
 *   <li><b>3차</b>: Discord Critical Alert + Metric</li>
 * </ol>
 *
 * <h3>SOLID 준수 (Blue 리팩토링)</h3>
 * <ul>
 *   <li>SRP: 메트릭 로직을 OutboxMetrics로 위임</li>
 *   <li>DIP: 인터페이스 의존</li>
 * </ul>
 *
 * @see DonationDlqRepository
 * @see ShutdownDataPersistenceService
 * @see DiscordAlertService
 * @see OutboxMetrics
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqHandler {

    private final DonationDlqRepository dlqRepository;
    private final ShutdownDataPersistenceService fileBackupService;
    private final DiscordAlertService discordAlertService;
    private final LogicExecutor executor;
    private final OutboxMetrics metrics;

    /**
     * Triple Safety Net 실행
     *
     * @param entry  실패한 Outbox 엔티티
     * @param reason 실패 사유
     */
    public void handleDeadLetter(DonationOutbox entry, String reason) {
        TaskContext context = TaskContext.of("DLQ", "Handle", entry.getRequestId());

        // 1차 시도: DB DLQ
        executor.executeOrCatch(
                () -> {
                    DonationDlq dlq = DonationDlq.from(entry, reason);
                    dlqRepository.save(dlq);
                    metrics.incrementDlq();
                    log.warn("⚠️ [DLQ] Entry moved to DLQ: {}", entry.getRequestId());
                    return null;
                },
                dbEx -> handleDbDlqFailure(entry, reason, context),
                context
        );
    }

    /**
     * 2차 시도: File Backup (DB DLQ 실패 시)
     */
    private Void handleDbDlqFailure(DonationOutbox entry, String reason, TaskContext context) {
        log.error("❌ [DLQ] DB DLQ 저장 실패, File Backup 시도: {}", entry.getRequestId());

        executor.executeOrCatch(
                () -> {
                    // Outbox payload를 파일로 백업
                    fileBackupService.appendOutboxEntry(entry.getRequestId(), entry.getPayload());
                    metrics.incrementFileBackup();
                    log.warn("📁 [DLQ] File Backup 성공: {}", entry.getRequestId());
                    return null;
                },
                fileEx -> handleCriticalFailure(entry, reason, fileEx),
                context
        );
        return null;
    }

    /**
     * 3차: Critical Alert (최후의 안전망)
     */
    private Void handleCriticalFailure(DonationOutbox entry, String reason, Throwable fileEx) {
        metrics.incrementCriticalFailure();

        String title = "🚨 OUTBOX CRITICAL FAILURE";
        String description = String.format(
                "RequestId: %s%nReason: %s%nManual intervention required!",
                entry.getRequestId(), reason
        );

        discordAlertService.sendCriticalAlert(title, description, (Exception) fileEx);
        log.error("🚨 [CRITICAL] All safety nets failed for: {} - Manual intervention required!",
                entry.getRequestId());

        return null;
    }
}
