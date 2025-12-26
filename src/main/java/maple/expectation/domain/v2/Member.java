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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String uuid;

    private Long point = 0L;

    // 🚀 [추가] 고정 UUID가 필요한 시스템 관리자용 팩토리 메서드
    public static Member createSystemAdmin(String uuid, Long initialPoint) {
        Member member = new Member();
        member.uuid = uuid;
        member.point = initialPoint;
        return member;
    }

    // 💡 기존에 있던 게스트용 팩토리 메서드
    public static Member createGuest(Long initialPoint) {
        Member member = new Member();
        member.uuid = UUID.randomUUID().toString();
        member.point = initialPoint;
        return member;
    }

    // ... (이하 비즈니스 로직 동일)
}