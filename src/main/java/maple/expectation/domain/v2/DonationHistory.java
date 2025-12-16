package maple.expectation.domain.v2;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "donation_history", uniqueConstraints = {
    // 💡 핵심: request_id 컬럼에 유니크 제약 조건을 걸어 '물리적으로' 중복 저장을 막습니다.
    @UniqueConstraint(name = "uk_donation_request_id", columnNames = "request_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class) // 생성 시간 자동 기록용
public class DonationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String senderUuid; // 보내는 사람 (Guest)

    @Column(nullable = false)
    private Long receiverId;   // 받는 사람 (Developer)

    @Column(nullable = false)
    private Long amount;       // 금액

    // 🔥 멱등성의 핵심 키 (UUID 등 고유 식별자)
    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public DonationHistory(String senderUuid, Long receiverId, Long amount, String requestId) {
        this.senderUuid = senderUuid;
        this.receiverId = receiverId;
        this.amount = amount;
        this.requestId = requestId;
    }
}