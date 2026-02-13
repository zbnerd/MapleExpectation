package maple.expectation.service.v2.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.alert.StatelessAlertService;
import maple.expectation.domain.v2.NexonApiDlq;
import maple.expectation.domain.v2.NexonApiOutbox;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.repository.v2.NexonApiDlqRepository;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.springframework.stereotype.Service;

/**
 * Dead Letter Queue 처리 서비스 for Nexon API (Issue #333)
 *
 * <h3>Triple Safety Net (P0 - 데이터 영구 손실 방지)</h3>
 *
 * <ol>
 *   <li><b>1차</b>: DB DLQ INSERT
 *   <li><b>2차</b>: File Backup (DLQ 실패 시)
 *   <li><b>3차</b>: Stateless Critical Alert + Metric
 * </ol>
 *
 * <h3>Design Pattern</h3>
 *
 * <p>DonationDlqHandler 패턴을 따르며 Nexon API 특화
 *
 * <h3>P0/P1 리팩토링 준수</h3>
 *
 * <ul>
 *   <li>P1-6: 3-Line Rule 준수 — 람다 -> 메서드 추출
 *   <li>CLAUDE.md Section 12: LogicExecutor Pattern (zero try-catch)
 *   <li>CLAUDE.md Section 6: @RequiredArgsConstructor (NO @Autowired)
 * </ul>
 *
 * @see NexonApiDlqRepository
 * @see ShutdownDataPersistenceService
 * @see StatelessAlertService
 * @see NexonApiOutboxMetrics
 * @see maple.expectation.service.v2.donation.outbox.DlqHandler
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NexonApiDlqHandler {

  private final NexonApiDlqRepository dlqRepository;
  private final ShutdownDataPersistenceService fileBackupService;
  private final StatelessAlertService statelessAlertService;
  private final LogicExecutor executor;
  private final NexonApiOutboxMetrics metrics;

  /**
   * Triple Safety Net 실행
   *
   * <p>NexonApiOutbox 항목 처리 실패 시 DLQ로 이동
   *
   * @param entry 실패한 Outbox 엔티티
   * @param cause 실패 원인 예외
   */
  public void handleDeadLetter(NexonApiOutbox entry, Throwable cause) {
    TaskContext context = TaskContext.of("NexonApiDLQ", "Handle", entry.getRequestId());

    executor.executeOrCatch(
        () -> saveToDbDlq(entry, cause),
        dbEx -> handleDbDlqFailure(entry, cause, context),
        context);
  }

  /**
   * Triple Safety Net 실행 (String reason 오버로딩)
   *
   * <p>무결성 검증 실패 등 예외가 아닌 경우 사용
   *
   * @param entry 실패한 Outbox 엔티티
   * @param reason 실패 사유
   */
  public void handleDeadLetter(NexonApiOutbox entry, String reason) {
    TaskContext context = TaskContext.of("NexonApiDLQ", "Handle", entry.getRequestId());

    executor.executeOrCatch(
        () -> saveToDbDlq(entry, reason),
        dbEx -> handleDbDlqFailure(entry, reason, context),
        context);
  }

  /** 1차 안전망: DB DLQ INSERT (P1-6: 메서드 추출) */
  private Void saveToDbDlq(NexonApiOutbox entry, Throwable cause) {
    String reason = cause != null ? cause.getMessage() : "Unknown error";
    return saveToDbDlq(entry, reason);
  }

  /** 1차 안전망: DB DLQ INSERT (String reason) */
  private Void saveToDbDlq(NexonApiOutbox entry, String reason) {
    NexonApiDlq dlq = NexonApiDlq.from(entry, reason);
    dlqRepository.save(dlq);
    metrics.incrementDlqMoved();
    log.warn(
        "[NexonApiDLQ] Entry moved to DLQ: requestId={}, reason={}", entry.getRequestId(), reason);
    return null;
  }

  /** 2차 안전망: File Backup (DB DLQ 실패 시) */
  private Void handleDbDlqFailure(NexonApiOutbox entry, Throwable cause, TaskContext context) {
    String reason = cause != null ? cause.getMessage() : "Unknown error";
    log.error("[NexonApiDLQ] DB DLQ 저장 실패, File Backup 시도: requestId={}", entry.getRequestId());

    executor.executeOrCatch(
        () -> saveToFileBackup(entry),
        fileEx -> handleCriticalFailure(entry, reason, fileEx),
        context);
    return null;
  }

  /** 2차 안전망: File Backup (String reason 오버로딩) */
  private Void handleDbDlqFailure(NexonApiOutbox entry, String reason, TaskContext context) {
    log.error("[NexonApiDLQ] DB DLQ 저장 실패, File Backup 시도: requestId={}", entry.getRequestId());

    executor.executeOrCatch(
        () -> saveToFileBackup(entry),
        fileEx -> handleCriticalFailure(entry, reason, fileEx),
        context);
    return null;
  }

  /** File Backup 실행 (P1-6: 메서드 추출) */
  private Void saveToFileBackup(NexonApiOutbox entry) {
    fileBackupService.appendOutboxEntry(entry.getRequestId(), entry.getPayload());
    metrics.incrementDlqFileBackup();
    log.warn("[NexonApiDLQ] File Backup 성공: requestId={}", entry.getRequestId());
    return null;
  }

  /**
   * 3차 안전망: Critical Alert (최후의 안전망)
   *
   * <h4>ADR-016 Triple Safety Net 패턴</h4>
   *
   * <p>DB, File 모두 실패 시 Discord 알림으로 운영자에게 수동 복구 요청
   *
   * @param entry 실패한 Outbox 엔티티
   * @param reason 실패 사유
   * @param fileEx File Backup 실패 예외
   */
  private Void handleCriticalFailure(NexonApiOutbox entry, String reason, Throwable fileEx) {
    metrics.incrementDlqCriticalFailure();

    String title = "NEXON API OUTBOX CRITICAL FAILURE";
    String description =
        String.format(
            "RequestId: %s%nEventType: %s%nReason: %s%nManual intervention required!",
            entry.getRequestId(), entry.getEventType(), reason);

    statelessAlertService.sendCritical(title, description, fileEx);
    log.error(
        "[CRITICAL] All safety nets failed for NexonApiOutbox: requestId={}, eventType={} - Manual intervention required!",
        entry.getRequestId(),
        entry.getEventType());

    return null;
  }
}
