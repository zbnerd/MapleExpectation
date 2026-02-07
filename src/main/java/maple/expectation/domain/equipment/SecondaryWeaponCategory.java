package maple.expectation.domain.equipment;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 보조무기 분류 Enum (#240 V4)
 *
 * <h3>보조무기 잠재능력 분류 규칙</h3>
 *
 * <p>item_equipment_slot = "보조무기"일 때:
 *
 * <ul>
 *   <li>item_equipment_part가 "포스실드" 또는 "소울링" → FORCE_SHIELD 잠재
 *   <li>그 외 → STANDARD_SECONDARY 잠재
 * </ul>
 *
 * <h3>잠재능력 확률 테이블 차이</h3>
 *
 * <ul>
 *   <li>STANDARD_SECONDARY: 보조무기 확률 테이블 사용
 *   <li>FORCE_SHIELD: 포스실드 확률 테이블 사용 (더 높은 보공/마공 확률)
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum SecondaryWeaponCategory {

  /**
   * 일반 보조무기 (방패, 책, 부적 등)
   *
   * <p>보조무기 잠재 확률 테이블 사용
   */
  STANDARD_SECONDARY("보조무기"),

  /**
   * 포스실드/소울링 (데몬어벤저, 제논 등)
   *
   * <p>포스실드 잠재 확률 테이블 사용
   */
  FORCE_SHIELD("포스실드");

  private final String potentialPart;

  /**
   * 보조무기 세부 분류에 따른 카테고리 결정
   *
   * @param itemEquipmentPart item_equipment_part 값 (e.g., "포스실드", "소울링", "블레이드")
   * @return 적절한 보조무기 카테고리
   */
  public static SecondaryWeaponCategory classify(String itemEquipmentPart) {
    if (itemEquipmentPart == null) {
      return STANDARD_SECONDARY;
    }

    String part = itemEquipmentPart.trim();
    if (part.contains("포스실드") || part.contains("소울링")) {
      return FORCE_SHIELD;
    }

    return STANDARD_SECONDARY;
  }

  /**
   * 잠재능력 계산 시 사용할 부위명 반환
   *
   * @param itemEquipmentSlot 장비 슬롯 (e.g., "보조무기", "무기")
   * @param itemEquipmentPart 장비 세부 분류 (e.g., "포스실드", "블레이드")
   * @return 잠재능력 확률 테이블 조회용 부위명
   */
  public static String resolvePotentialPart(String itemEquipmentSlot, String itemEquipmentPart) {
    // 보조무기가 아닌 경우 그대로 반환
    if (!"보조무기".equals(itemEquipmentSlot)) {
      return itemEquipmentSlot;
    }

    // 보조무기인 경우 세부 분류에 따라 결정
    return classify(itemEquipmentPart).getPotentialPart();
  }
}
