package maple.expectation.service.v2.donation.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.DonationDlq;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.infrastructure.alert.StatelessAlertService;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.repository.DonationDlqRepository;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.springframework.stereotype.Service;

/**
 * Dead Letter Queue 처리 서비스 (Issue #80)
 *
 * <h3>Triple Safety Net (P0 - 데이터 영구 손실 방지)</h3>
 *
 * <ol>
 *   <li><b>1차</b>: DB DLQ INSERT
 *   <li><b>2차</b>: File Backup (DLQ 실패 시)
 *   <li><b>3차</b>: Stateless Critical Alert + Metric
 * </ol>
 *
 * <h3>P0/P1 리팩토링</h3>
 *
 * <ul>
 *   <li>P0-3: ClassCastException 수정 — Throwable 다운캐스트 제거
 *   <li>P1-6: 3-Line Rule 준수 — 람다 -> 메서드 추출
 * </ul>
 *
 * @see DonationDlqRepository
 * @see ShutdownDataPersistenceService
 * @see StatelessAlertService
 * @see OutboxMetrics
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqHandler {

  private final DonationDlqRepository dlqRepository;
  private final ShutdownDataPersistenceService fileBackupService;
  private final StatelessAlertService statelessAlertService;
  private final LogicExecutor executor;
  private final OutboxMetrics metrics;

  /**
   * Triple Safety Net 실행
   *
   * @param entry 실패한 Outbox 엔티티
   * @param reason 실패 사유
   */
  public void handleDeadLetter(DonationOutbox entry, String reason) {
    TaskContext context = TaskContext.of("DLQ", "Handle", entry.getRequestId());

    executor.executeOrCatch(
        () -> saveToDbDlq(entry, reason),
        dbEx -> handleDbDlqFailure(entry, reason, context),
        context);
  }

  /** 1차 안전망: DB DLQ INSERT (P1-6: 메서드 추출) */
  private Void saveToDbDlq(DonationOutbox entry, String reason) {
    DonationDlq dlq = DonationDlq.from(entry, reason);
    dlqRepository.save(dlq);
    metrics.incrementDlq();
    log.warn("[DLQ] Entry moved to DLQ: {}", entry.getRequestId());
    return null;
  }

  /** 2차 안전망: File Backup (DB DLQ 실패 시) */
  private Void handleDbDlqFailure(DonationOutbox entry, String reason, TaskContext context) {
    log.error("[DLQ] DB DLQ 저장 실패, File Backup 시도: {}", entry.getRequestId());

    executor.executeOrCatch(
        () -> saveToFileBackup(entry),
        fileEx -> handleCriticalFailure(entry, reason, fileEx),
        context);
    return null;
  }

  /** File Backup 실행 (P1-6: 메서드 추출) */
  private Void saveToFileBackup(DonationOutbox entry) {
    fileBackupService.appendOutboxEntry(entry.getRequestId(), entry.getPayload());
    metrics.incrementFileBackup();
    log.warn("[DLQ] File Backup 성공: {}", entry.getRequestId());
    return null;
  }

  /**
   * 3차 안전망: Critical Alert (최후의 안전망)
   *
   * <h4>P0-3 Fix: ClassCastException 제거</h4>
   *
   * <p>기존: {@code (Exception) fileEx} — Throwable -> Exception 다운캐스트 시 Error(OOM 등)에서
   * ClassCastException 발생 -> Triple Safety Net 완전 실패
   *
   * <p>수정: Throwable 그대로 전달
   */
  private Void handleCriticalFailure(DonationOutbox entry, String reason, Throwable fileEx) {
    metrics.incrementCriticalFailure();

    String title = "OUTBOX CRITICAL FAILURE";
    String description =
        String.format(
            "RequestId: %s%nReason: %s%nManual intervention required!",
            entry.getRequestId(), reason);

    statelessAlertService.sendCritical(title, description, fileEx);
    log.error(
        "[CRITICAL] All safety nets failed for: {} - Manual intervention required!",
        entry.getRequestId());

    return null;
  }
}
