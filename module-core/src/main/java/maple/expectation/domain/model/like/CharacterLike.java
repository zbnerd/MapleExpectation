package maple.expectation.domain.model.like;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 캐릭터 좋아요 도메인 모델
 *
 * <p>순수 도메인 - JPA 의존 없음
 *
 * <h3>SOLID 준수</h3>
 *
 * <ul>
 *   <li>SRP: 좋아요 관계 표현만 담당
 *   <li>OCP: 불변 record로 안전한 상태 보장
 * </ul>
 */
public record CharacterLike(
    Long id, String targetOcid, String likerAccountId, LocalDateTime createdAt) {

  /** 새 좋아요 생성 */
  public static CharacterLike create(String targetOcid, String likerAccountId) {
    Objects.requireNonNull(targetOcid, "targetOcid cannot be null");
    Objects.requireNonNull(likerAccountId, "likerAccountId cannot be null");
    return new CharacterLike(null, targetOcid, likerAccountId, LocalDateTime.now());
  }

  /**
   * 영속 레이어 복원 전용
   *
   * <p>JPA/Redis에서 전체 필드 복원 시 사용
   */
  public static CharacterLike restore(
      Long id, String targetOcid, String likerAccountId, LocalDateTime createdAt) {
    return new CharacterLike(id, targetOcid, likerAccountId, createdAt);
  }

  /** ID가 할당된 새 인스턴스 반환 (영속화 후) */
  public CharacterLike withId(Long id) {
    return new CharacterLike(id, targetOcid, likerAccountId, createdAt);
  }

  /** 자기 좋아요 여부 확인 */
  public boolean isSelfLike() {
    return targetOcid != null && targetOcid.equals(likerAccountId);
  }

  /** 새 좋아요 여부 (ID 없음) */
  public boolean isNew() {
    return id == null;
  }

  /**
   * Factory method for creating CharacterLike from existing data
   *
   * <p>Used by DTOs to convert back to domain model
   *
   * @param id the like ID
   * @param targetOcid target character OCID
   * @param likerAccountId the account ID of the user who liked
   * @param createdAt creation timestamp
   * @return CharacterLike instance
   */
  public static CharacterLike of(
      Long id, String targetOcid, String likerAccountId, LocalDateTime createdAt) {
    return new CharacterLike(id, targetOcid, likerAccountId, createdAt);
  }

  /**
   * Factory method for creating new CharacterLike (without ID)
   *
   * <p>Used by DTOs to convert new likes
   *
   * @param targetOcid target character OCID
   * @param likerAccountId the account ID of the user who liked
   * @return CharacterLike instance with current timestamp
   */
  public static CharacterLike of(String targetOcid, String likerAccountId) {
    return new CharacterLike(null, targetOcid, likerAccountId, LocalDateTime.now());
  }
}
