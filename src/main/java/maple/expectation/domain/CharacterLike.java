package maple.expectation.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 캐릭터 좋아요 Entity
 *
 * <p>중복 방지 전략:
 * <ul>
 *   <li>UNIQUE 제약조건: (target_ocid, liker_fingerprint)</li>
 *   <li>동일 계정(fingerprint)이 같은 캐릭터(ocid)에 중복 좋아요 불가</li>
 * </ul>
 * </p>
 *
 * <p>Self-Like 방지는 서비스 레이어에서 myOcids 검증으로 처리</p>
 */
@Entity
@Table(
    name = "character_like",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_target_liker",
        columnNames = {"target_ocid", "liker_fingerprint"}
    ),
    indexes = @Index(name = "idx_target_ocid", columnList = "target_ocid")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_ocid", nullable = false, length = 64)
    private String targetOcid;

    @Column(name = "liker_fingerprint", nullable = false, length = 64)
    private String likerFingerprint;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 좋아요 생성
     *
     * @param targetOcid       좋아요 대상 캐릭터 OCID
     * @param likerFingerprint 좋아요를 누른 계정의 fingerprint
     */
    public CharacterLike(String targetOcid, String likerFingerprint) {
        this.targetOcid = targetOcid;
        this.likerFingerprint = likerFingerprint;
    }

    /**
     * 팩토리 메서드
     */
    public static CharacterLike of(String targetOcid, String likerFingerprint) {
        return new CharacterLike(targetOcid, likerFingerprint);
    }
}
