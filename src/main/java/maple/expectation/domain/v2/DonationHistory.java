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
    private Long receiverId;
    private Long amount;

    @Column(updatable = false) // 💡 실수로라도 수정되는 것을 방지
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