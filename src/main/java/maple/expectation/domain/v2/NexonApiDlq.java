package maple.expectation.domain.v2;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Dead Letter Queue 엔티티 for Nexon API Outbox (Issue #333)
 *
 * <h3>Triple Safety Net 1차 안전망</h3>
 *
 * <p>NexonApiOutbox 처리 실패 시 데이터 영구 손실을 방지하기 위한 DLQ 테이블.
 *
 * <ul>
 *   <li>1차: DB DLQ INSERT (이 엔티티)
 *   <li>2차: File Backup (DLQ 실패 시)
 *   <li>3차: Discord Critical Alert
 * </ul>
 *
 * <h3>Design Pattern</h3>
 *
 * <p>DonationDlq 패턴을 따르며 Nexon API 특화 필드 포함
 *
 * @see maple.expectation.service.v2.outbox.NexonApiDlqHandler
 * @see maple.expectation.domain.v2.NexonApiOutbox
 */
@Entity
@Table(
    name = "nexon_api_dlq",
    indexes = {
      @Index(name = "idx_dlq_moved_at", columnList = "moved_at"),
      @Index(name = "idx_dlq_request_id", columnList = "request_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NexonApiDlq {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long originalOutboxId;

  @Column(nullable = false, length = 100)
  private String requestId;

  @Column(nullable = false, length = 50)
  private String eventType;

  @Column(columnDefinition = "TEXT", nullable = false)
  private String payload;

  @Column(length = 500)
  private String failureReason;

  @Column(updatable = false)
  private LocalDateTime movedAt;

  /**
   * Outbox 엔티티에서 DLQ 엔티티 생성
   *
   * @param outbox 실패한 NexonApiOutbox 엔티티
   * @param reason 실패 사유
   * @return NexonApiDlq 엔티티
   */
  public static NexonApiDlq from(NexonApiOutbox outbox, String reason) {
    NexonApiDlq dlq = new NexonApiDlq();
    dlq.originalOutboxId = outbox.getId();
    dlq.requestId = outbox.getRequestId();
    dlq.eventType = outbox.getEventType().name();
    dlq.payload = outbox.getPayload();
    dlq.failureReason = truncate(reason, 500);
    dlq.movedAt = LocalDateTime.now();
    return dlq;
  }

  /**
   * 문자열 자르기 (DB 컬럼 길이 제한 준수)
   *
   * @param str 원본 문자열
   * @param maxLen 최대 길이
   * @return 자른 문자열
   */
  private static String truncate(String str, int maxLen) {
    return str != null && str.length() > maxLen ? str.substring(0, maxLen) : str;
  }

  /**
   * PII 마스킹 (CLAUDE.md Section 19 준수)
   *
   * <p>로그 출력 시 payload 내용을 마스킹하여 민감 정보 노출 방지
   */
  @Override
  public String toString() {
    return "NexonApiDlq[id="
        + id
        + ", requestId="
        + requestId
        + ", eventType="
        + eventType
        + ", failureReason="
        + failureReason
        + ", payload=MASKED]";
  }
}
