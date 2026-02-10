package maple.expectation.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 캐릭터 좋아요 Entity
 *
 * <p>중복 방지 전략:
 *
 * <ul>
 *   <li>UNIQUE 제약조건: (target_ocid, liker_account_id)
 *   <li>동일 넥슨 계정(accountId)이 같은 캐릭터(ocid)에 중복 좋아요 불가
 * </ul>
 *
 * <p>Self-Like 방지는 서비스 레이어에서 myOcids 검증으로 처리
 */
@Entity
@Table(
    name = "character_like",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_target_liker",
            columnNames = {"target_ocid", "liker_account_id"}),
    indexes = {
      @Index(name = "idx_target_ocid", columnList = "target_ocid"),
      @Index(name = "idx_liker_account_id", columnList = "liker_account_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CharacterLike {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "target_ocid", nullable = false, length = 64)
  private String targetOcid;

  @Column(name = "liker_account_id", nullable = false, length = 64)
  private String likerAccountId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /**
   * 좋아요 생성
   *
   * @param targetOcid 좋아요 대상 캐릭터 OCID
   * @param likerAccountId 좋아요를 누른 넥슨 계정 식별자
   */
  public CharacterLike(String targetOcid, String likerAccountId) {
    this.targetOcid = targetOcid;
    this.likerAccountId = likerAccountId;
  }

  /** 팩토리 메서드 */
  public static CharacterLike of(String targetOcid, String likerAccountId) {
    return new CharacterLike(targetOcid, likerAccountId);
  }
}
