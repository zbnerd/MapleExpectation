package maple.expectation.domain.v2;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /**
     * 장비 데이터 (LAZY 로딩)
     *
     * <p><b>P1 버그 수정 (PR #125 Codex 지적)</b>:
     * {@code @JsonIgnore}로 JSON 응답에서 제외.
     * 200-400KB blob이 API 응답에 노출되면 보안 및 성능 문제 발생.
     *
     * <p>optional = true (기본값)로 두면 장비 데이터가 없어도 캐릭터 생성이 가능해집니다.
     */
    @Setter
    @JsonIgnore  // P1 Fix: 장비 blob JSON 노출 방지
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