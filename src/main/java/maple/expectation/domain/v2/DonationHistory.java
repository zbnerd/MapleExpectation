package maple.expectation.domain.v2;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "donation_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_request_id", columnNames = "request_id"))
public class DonationHistory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String senderUuid;

    /**
     * Admin(개발자)의 fingerprint
     * <p>보안: fingerprint는 HMAC-SHA256 해시값이므로 저장해도 원본 API Key 노출 없음</p>
     */
    private String receiverFingerprint;

    private Long amount;

    @Column(updatable = false)
    private String requestId;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public DonationHistory(String senderUuid, String receiverFingerprint, Long amount, String requestId) {
        this.senderUuid = senderUuid;
        this.receiverFingerprint = receiverFingerprint;
        this.amount = amount;
        this.requestId = requestId;
    }
}