package maple.expectation.domain.model.character;

import java.time.LocalDateTime;
import maple.expectation.domain.model.equipment.CharacterEquipment;

/**
 * 게임 캐릭터 도메인 모델 (순수 도메인)
 *
 * <p>JPA 엔티티는 module-infra에 별도로 존재
 *
 * <p>이 클래스는 비즈니스 로직에서 사용하는 순수 도메인 모델
 *
 * <h3>SOLID 준수</h3>
 *
 * <ul>
 *   <li>SRP: 캐릭터 데이터 표현 및 관련 비즈니스 규칙만 담당
 *   <li>OCP: 불변 필드 + with* 메서드로 안전한 상태 변화
 * </ul>
 */
public record GameCharacter(
    Long id,
    UserIgn userIgn,
    CharacterId characterId,
    CharacterEquipment equipment,
    String worldName,
    String characterClass,
    String characterImage,
    LocalDateTime basicInfoUpdatedAt,
    Long likeCount,
    Long version,
    LocalDateTime updatedAt) {

  /** 새 캐릭터 생성 (최소 필드만) */
  public static GameCharacter create(UserIgn userIgn, CharacterId characterId) {
    return new GameCharacter(
        null, userIgn, characterId, null, null, null, null, null, 0L, null, LocalDateTime.now());
  }

  /**
   * JPA/Redis 복원을 위한 정적 팩토리
   *
   * <p>Persist 레이어에서 전체 필드를 복원할 때 사용
   */
  public static GameCharacter restore(
      Long id,
      CharacterId characterId,
      UserIgn userIgn,
      CharacterEquipment equipment,
      String worldName,
      String characterClass,
      String characterImage,
      LocalDateTime basicInfoUpdatedAt,
      Long likeCount,
      Long version,
      LocalDateTime updatedAt) {
    return new GameCharacter(
        id,
        userIgn,
        characterId,
        equipment,
        worldName,
        characterClass,
        characterImage,
        basicInfoUpdatedAt,
        likeCount,
        version,
        updatedAt);
  }

  /** 장비 정보 포함된 새 인스턴스 반환 */
  public GameCharacter withEquipment(CharacterEquipment equipment) {
    return new GameCharacter(
        id,
        userIgn,
        characterId,
        equipment,
        worldName,
        characterClass,
        characterImage,
        basicInfoUpdatedAt,
        likeCount,
        version,
        LocalDateTime.now());
  }

  /** 기본 정보 업데이트된 새 인스턴스 반환 */
  public GameCharacter withBasicInfo(
      String worldName, String characterClass, String characterImage) {
    return new GameCharacter(
        id,
        userIgn,
        characterId,
        equipment,
        worldName,
        characterClass,
        characterImage,
        LocalDateTime.now(),
        likeCount,
        version,
        LocalDateTime.now());
  }

  /** 좋아요 수 증가된 새 인스턴스 반환 */
  public GameCharacter withIncrementedLike() {
    return new GameCharacter(
        id,
        userIgn,
        characterId,
        equipment,
        worldName,
        characterClass,
        characterImage,
        basicInfoUpdatedAt,
        likeCount + 1,
        version,
        LocalDateTime.now());
  }

  /** 버전 증가된 새 인스턴스 반환 (낙관적 락) */
  public GameCharacter withNextVersion() {
    return new GameCharacter(
        id,
        userIgn,
        characterId,
        equipment,
        worldName,
        characterClass,
        characterImage,
        basicInfoUpdatedAt,
        likeCount,
        version != null ? version + 1 : 1L,
        LocalDateTime.now());
  }

  /** ID가 할당된 새 인스턴스 반환 (영속化 후) */
  public GameCharacter withId(Long id) {
    return new GameCharacter(
        id,
        userIgn,
        characterId,
        equipment,
        worldName,
        characterClass,
        characterImage,
        basicInfoUpdatedAt,
        likeCount,
        version,
        updatedAt);
  }

  /** 장비 데이터 존재 여부 */
  public boolean hasEquipment() {
    return equipment != null && equipment.hasData();
  }

  /** 기본 정보 존재 여부 */
  public boolean hasBasicInfo() {
    return worldName != null && !worldName.isBlank();
  }

  /** 새 캐릭터 여부 (ID 없음) */
  public boolean isNew() {
    return id == null;
  }

  /** OCID 반환 (편의 메서드) */
  public String getOcid() {
    return characterId != null ? characterId.value() : null;
  }

  /** UserIGN 반환 (JPA 매핑용) */
  public UserIgn getUserIgn() {
    return userIgn;
  }

  /** LikerAccountId 반환 (좋아요 매핑용) */
  public UserIgn getLikerAccountId() {
    return userIgn;
  }
}
