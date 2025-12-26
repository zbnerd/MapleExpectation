package maple.expectation.domain.v2;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import maple.expectation.global.error.exception.InsufficientPointException;

import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = @Index(name = "idx_uuid", columnList = "uuid", unique = true))
public class Member {

    // 1. 성능용 내부 ID (MySQL PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 2. 외부용 식별 ID (Guest 식별자)
    @Column(nullable = false, unique = true, length = 36)
    private String uuid;

    // 3. 핵심: 돈 (기본값 0원)
    @Column(nullable = false)
    private Long point = 0L;

    // 생성자: Guest 생성 시 UUID를 자동 발급하거나 받아서 넣음
    public Member(Long initialPoint) {
        this.uuid = UUID.randomUUID().toString(); // 생성 시 자동 발급
        this.point = initialPoint;
    }

    // 개발자(Admin)용 생성자 (UUID 고정 필요 시 사용)
    public Member(String fixedUuid, Long initialPoint) {
        this.uuid = fixedUuid;
        this.point = initialPoint;
    }

    // --- 비즈니스 로직 ---
    public void decreasePoint(Long amount) {
        if (this.point < amount) {
            throw new InsufficientPointException("잔액이 부족합니다.");
        }
        this.point -= amount;
    }

    public void increasePoint(Long amount) {
        this.point += amount;
    }
}