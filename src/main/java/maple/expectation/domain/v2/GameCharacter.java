package maple.expectation.domain.v2;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import jakarta.persistence.*;
import maple.expectation.global.error.exception.InvalidCharacterStateException;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;

import java.time.LocalDateTime;

/**
 * GameCharacter 엔티티 (Rich Domain Model)
 *
 * <p>Issue #120: 캐릭터 상태 검증 로직 캡슐화</p>
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(exclude = "equipment")
public class GameCharacter {

    private static final int ACTIVE_DAYS_THRESHOLD = 30;

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
     */
    @Setter
    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "ocid", referencedColumnName = "ocid",
            insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    @NotFound(action = NotFoundAction.IGNORE)
    private CharacterEquipment equipment;

    @Version
    private Long version;

    private Long likeCount = 0L;

    private LocalDateTime updatedAt;

    public GameCharacter(String userIgn, String ocid) {
        validateOcidInternal(ocid);
        this.userIgn = userIgn;
        this.ocid = ocid;
        this.likeCount = 0L;
        this.updatedAt = LocalDateTime.now();
    }

    // ==================== Business Logic (기존) ====================

    public void like() {
        this.likeCount++;
    }

    // ==================== Business Logic (Issue #120) ====================

    /**
     * 활성 캐릭터 여부 확인 (30일 이내 업데이트)
     *
     * @return 활성 상태면 true
     */
    public boolean isActive() {
        return this.updatedAt != null &&
                this.updatedAt.isAfter(LocalDateTime.now().minusDays(ACTIVE_DAYS_THRESHOLD));
    }

    /**
     * OCID 유효성 검증
     *
     * @throws InvalidCharacterStateException OCID가 null이거나 비어있는 경우
     */
    public void validateOcid() {
        validateOcidInternal(this.ocid);
    }

    // ==================== Private Helpers ====================

    private void validateOcidInternal(String ocidValue) {
        if (ocidValue == null || ocidValue.isBlank()) {
            throw new InvalidCharacterStateException("OCID는 필수입니다");
        }
    }
}