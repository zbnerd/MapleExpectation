package maple.expectation.domain.v2;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import maple.expectation.global.error.exception.InsufficientPointException;

import java.util.UUID;

/**
 * Member 엔티티 (Rich Domain Model)
 *
 * <p>Issue #120: Anemic → Rich Domain Model 전환</p>
 * <p>포인트 관련 비즈니스 로직을 엔티티 내부에 캡슐화합니다.</p>
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = @Index(name = "idx_uuid", columnList = "uuid", unique = true))
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 낙관적 락 버전 (Issue #120 Rich Domain 동시성 보호)
     *
     * <p>Rich Domain Model에서 메모리 연산 후 DB 반영 시
     * 동시 요청에 의한 Lost Update 방지</p>
     */
    @Version
    private Long version;

    @Column(nullable = false, unique = true, length = 36)
    private String uuid;

    private Long point = 0L;

    // ==================== Factory Methods ====================

    /**
     * 시스템 관리자용 팩토리 메서드 (고정 UUID)
     */
    public static Member createSystemAdmin(String uuid, Long initialPoint) {
        Member member = new Member();
        member.uuid = uuid;
        member.point = initialPoint;
        return member;
    }

    /**
     * 게스트용 팩토리 메서드 (랜덤 UUID)
     */
    public static Member createGuest(Long initialPoint) {
        Member member = new Member();
        member.uuid = UUID.randomUUID().toString();
        member.point = initialPoint;
        return member;
    }

    // ==================== Business Logic (Issue #120) ====================

    /**
     * 포인트 잔액 확인
     *
     * @param amount 확인할 금액
     * @return 잔액이 충분하면 true
     */
    public boolean hasEnoughPoint(Long amount) {
        return this.point >= amount;
    }

    /**
     * 포인트 차감 (Rich Domain Model)
     *
     * <p>잔액 부족 시 InsufficientPointException 발생</p>
     *
     * @param amount 차감할 금액
     * @throws InsufficientPointException 잔액 부족
     * @throws IllegalArgumentException 금액이 0 이하
     */
    public void deductPoints(Long amount) {
        validatePositiveAmount(amount);
        if (!hasEnoughPoint(amount)) {
            // InsufficientPointException은 (보유, 필요) 2개 인자를 받음
            throw new InsufficientPointException(this.point, amount);
        }
        this.point -= amount;
    }

    // ==================== Private Helpers ====================

    private void validatePositiveAmount(Long amount) {
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("금액은 양수여야 합니다: " + amount);
        }
    }

    private String maskUuid() {
        if (this.uuid == null || this.uuid.length() < 8) {
            return "****";
        }
        return this.uuid.substring(0, 4) + "****";
    }
}