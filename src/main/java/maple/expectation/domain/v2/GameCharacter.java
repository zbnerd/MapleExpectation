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
    private static final int BASIC_INFO_REFRESH_MINUTES = 15;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String userIgn;

    @Column(nullable = false, unique = true)
    private String ocid;

    /**
     * 월드명 (Nexon API character/basic에서 조회)
     */
    @Setter
    @Column(length = 50)
    private String worldName;

    /**
     * 직업명 (Nexon API character/basic에서 조회)
     */
    @Setter
    @Column(length = 50)
    private String characterClass;

    /**
     * 캐릭터 이미지 URL (Nexon API character/basic에서 조회)
     *
     * <p>URL이 매우 길 수 있으므로 2048자로 설정</p>
     */
    @Setter
    @Column(length = 2048)
    private String characterImage;

    /**
     * 캐릭터 기본 정보 마지막 업데이트 시각
     *
     * <p>character_image가 수시로 바뀌므로 15분 간격으로 갱신</p>
     */
    @Setter
    private LocalDateTime basicInfoUpdatedAt;

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

    /**
     * 캐릭터 기본 정보 갱신 필요 여부 확인
     *
     * <p>character_image가 수시로 바뀌므로 15분 간격으로 갱신 필요</p>
     *
     * @return 갱신 필요 시 true (worldName이 null이거나 15분 경과)
     */
    public boolean needsBasicInfoRefresh() {
        // worldName이 없으면 갱신 필요
        if (this.worldName == null) {
            return true;
        }
        // 마지막 업데이트 시각이 없거나 15분 이상 경과했으면 갱신 필요
        return this.basicInfoUpdatedAt == null ||
                this.basicInfoUpdatedAt.isBefore(LocalDateTime.now().minusMinutes(BASIC_INFO_REFRESH_MINUTES));
    }

    // ==================== Private Helpers ====================

    private void validateOcidInternal(String ocidValue) {
        if (ocidValue == null || ocidValue.isBlank()) {
            throw new InvalidCharacterStateException("OCID는 필수입니다");
        }
    }
}