package maple.expectation.domain.v2;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(exclude = "equipment")
public class GameCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String userIgn;

    @Column(nullable = false, unique = true)
    private String ocid;

    // 연관관계 편의 메서드
    // 💡 String ocid 필드와 별개로 '객체' 연관관계를 정의합니다.
    // optional = true (기본값)로 두면 장비 데이터가 없어도 캐릭터 생성이 가능해집니다.
    @Setter
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "ocid", referencedColumnName = "ocid",
            insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    @NotFound(action = NotFoundAction.IGNORE)
    private CharacterEquipment equipment;

    @Version
    private Long version;

    private Long likeCount = 0L;

    public GameCharacter(String userIgn, String ocid) {
        this.userIgn = userIgn;
        this.ocid = ocid;
        this.likeCount = 0L;
    }

    public void like() {
        this.likeCount++;
    }
}