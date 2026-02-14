package maple.expectation.domain.model.equipment;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import maple.expectation.domain.model.character.CharacterId;

/**
 * 캐릭터 장비 도메인 모델
 *
 * <p>순수 도메인 - JPA 의존 없음
 *
 * <h3>SOLID 준수</h3>
 *
 * <ul>
 *   <li>SRP: 장비 데이터 표현 및 관련 비즈니스 규칙만 담당
 *   <li>OCP: 불변 record로 안전한 상태 보장
 * </ul>
 */
public record CharacterEquipment(
    CharacterId characterId, EquipmentData equipmentData, LocalDateTime updatedAt) {

  /** 새 장비 생성 */
  public static CharacterEquipment create(CharacterId characterId, EquipmentData equipmentData) {
    return new CharacterEquipment(characterId, equipmentData, LocalDateTime.now());
  }

  /** 빈 장비 생성 (기본값) */
  public static CharacterEquipment createEmpty(CharacterId characterId) {
    return new CharacterEquipment(characterId, EquipmentData.empty(), LocalDateTime.now());
  }

  /**
   * 영속 레이어 복원 전용
   *
   * <p>JPA/Redis에서 전체 필드 복원 시 사용
   */
  public static CharacterEquipment restore(
      CharacterId characterId, EquipmentData equipmentData, LocalDateTime updatedAt) {
    return new CharacterEquipment(characterId, equipmentData, updatedAt);
  }

  /** OCID로 새 장비 생성 (편의 메서드) */
  public static CharacterEquipment of(String ocid, String json) {
    return new CharacterEquipment(
        CharacterId.of(ocid), EquipmentData.of(json), LocalDateTime.now());
  }

  /** 장비 데이터 업데이트된 새 인스턴스 반환 */
  public CharacterEquipment withUpdatedData(String newData) {
    return new CharacterEquipment(characterId, EquipmentData.of(newData), LocalDateTime.now());
  }

  /** 장비 데이터 업데이트된 새 인스턴스 반환 */
  public CharacterEquipment withUpdatedData(EquipmentData newData) {
    return new CharacterEquipment(characterId, newData, LocalDateTime.now());
  }

  /** 캐릭터 ID 변경된 새 인스턴스 반환 */
  public CharacterEquipment withCharacterId(CharacterId newCharacterId) {
    return new CharacterEquipment(newCharacterId, equipmentData, updatedAt);
  }

  /** 캐릭터 OCID 반환 */
  public String ocid() {
    return characterId != null ? characterId.value() : null;
  }

  /** 장비 데이터 JSON 컨텐츠 반환 */
  public String jsonContent() {
    return equipmentData != null ? equipmentData.jsonContent() : null;
  }

  /**
   * 데이터 신선성 확인 - updatedAt이 TTL 내에 있는지 확인
   *
   * @param ttl 캐시 유효 기간
   * @return true if updatedAt is within TTL from now
   */
  public boolean isFresh(Duration ttl) {
    return updatedAt != null
        && ChronoUnit.MILLIS.between(updatedAt, LocalDateTime.now()) < ttl.toMillis();
  }

  /** 데이터 존재 여부 확인 */
  public boolean hasData() {
    return equipmentData != null && equipmentData.isNotEmpty();
  }

  /** 데이터 신선성 만료 여부 */
  public boolean isStale(Duration ttl) {
    return !isFresh(ttl);
  }

  /** 빈 장비 여부 */
  public boolean isEmpty() {
    return !hasData();
  }
}
