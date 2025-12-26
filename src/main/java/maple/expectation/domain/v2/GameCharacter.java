package maple.expectation.domain.v2;

import lombok.*;
import jakarta.persistence.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 💡 무분별한 생성을 막고 JPA 프록시용으로 열어둠
@ToString(exclude = "id") // ID는 로그 출력 시 순환참조 방지
public class GameCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String userIgn;

    @Column(nullable = false, unique = true)
    private String ocid;

    @Version
    private Long version; // 낙관적 락(Optimistic Lock)을 위한 버전

    private Long likeCount = 0L;

    // 💡 생성자에서 필수 값을 강제함
    public GameCharacter(String userIgn, String ocid) {
        this.userIgn = userIgn;
        this.ocid = ocid;
        this.likeCount = 0L;
    }

    // --- 비즈니스 로직 (의미 있는 이름) ---

    public void syncOcid(String newOcid) {
        // 💡 Setter 대신 '동기화'라는 의미 부여
        if (newOcid == null || newOcid.isBlank()) throw new IllegalArgumentException("OCID는 필수입니다.");
        this.ocid = newOcid;
    }

    public void like() {
        this.likeCount++;
    }
}