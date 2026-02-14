package maple.expectation.domain.model.equipment;

/**
 * 장비 데이터 도메인 모델
 *
 * <p>순수 도메인 - JPA 의존 없음
 *
 * <h3>SOLID 준수</h3>
 *
 * <ul>
 *   <li>SRP: 장비 JSON 데이터 표현만 담당
 *   <li>OCP: 불변 record로 안전한 상태 보장
 * </ul>
 */
public record EquipmentData(String json) {

  /** 빈 JSON 생성자 (null 허용 안됨) */
  public EquipmentData {
    if (json == null) {
      throw new IllegalArgumentException("json cannot be null");
    }
  }

  /** 빈 장비 데이터 생성 */
  public static EquipmentData empty() {
    return new EquipmentData("{}");
  }

  /** JSON으로부터 생성 */
  public static EquipmentData of(String json) {
    return new EquipmentData(json);
  }

  /** JSON 컨텐츠 반환 */
  public String jsonContent() {
    return json;
  }

  /** 비어있는지 여부 확인 */
  public boolean isEmpty() {
    return json == null || json.isBlank() || "{}".equals(json.trim());
  }

  /** 비어있지 않은지 여부 확인 */
  public boolean isNotEmpty() {
    return !isEmpty();
  }

  /** JSON 길이 반환 */
  public int length() {
    return json != null ? json.length() : 0;
  }
}
