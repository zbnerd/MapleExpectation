package maple.expectation.domain.v2;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Dead Letter Queue 엔티티 (Issue #80)
 *
 * <h3>Triple Safety Net 1차 안전망</h3>
 * <p>Outbox 처리 실패 시 데이터 영구 손실을 방지하기 위한 DLQ 테이블.</p>
 * <ul>
 *   <li>1차: DB DLQ INSERT (이 엔티티)</li>
 *   <li>2차: File Backup (DLQ 실패 시)</li>
 *   <li>3차: Discord Critical Alert</li>
 * </ul>
 *
 * @see maple.expectation.service.v2.donation.outbox.application.DlqHandler
 */
@Entity
@Table(name = "donation_dlq",
        indexes = {
                @Index(name = "idx_dlq_moved_at", columnList = "moved_at"),
                @Index(name = "idx_dlq_request_id", columnList = "request_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DonationDlq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long originalOutboxId;

    @Column(nullable = false, length = 50)
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
     */
    public static DonationDlq from(DonationOutbox outbox, String reason) {
        DonationDlq dlq = new DonationDlq();
        dlq.originalOutboxId = outbox.getId();
        dlq.requestId = outbox.getRequestId();
        dlq.eventType = outbox.getEventType();
        dlq.payload = outbox.getPayload();
        dlq.failureReason = truncate(reason, 500);
        dlq.movedAt = LocalDateTime.now();
        return dlq;
    }

    private static String truncate(String str, int maxLen) {
        return str != null && str.length() > maxLen ? str.substring(0, maxLen) : str;
    }

    /**
     * PII 마스킹 (CLAUDE.md 19 준수)
     */
    @Override
    public String toString() {
        return "DonationDlq[id=" + id +
                ", requestId=" + requestId +
                ", failureReason=" + failureReason +
                ", payload=MASKED]";
    }
}
