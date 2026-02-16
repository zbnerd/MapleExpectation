package maple.expectation.service.v2.flame;

import lombok.RequiredArgsConstructor;
import maple.expectation.core.probability.FlameScoreCalculator.JobWeights;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.flame.component.FlameScoreResolver;
import maple.expectation.service.v2.flame.config.BossEquipmentRegistry;
import maple.expectation.service.v2.flame.config.JobStatMapping;
import org.springframework.stereotype.Component;

/**
 * 환생의 불꽃 입력 해석기
 *
 * <p>장비 데이터(CubeCalculationInput)와 직업명으로부터 불꽃 기대값 계산에 필요한 5가지 입력을 추출합니다:
 *
 * <ol>
 *   <li>보스 장비 여부
 *   <li>무기 여부
 *   <li>추옵 환산치 (목표값)
 *   <li>무기 기본 공/마
 *   <li>직업 가중치 (JobWeights)
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class FlameInputResolver {

  private static final String WEAPON_SLOT = "무기";

  private final FlameScoreResolver scoreResolver;

  /**
   * 불꽃 계산 입력 해석
   *
   * @param input 장비 데이터
   * @param characterClass 직업명
   * @return 해석된 불꽃 입력, 추옵이 없으면 null
   */
  public FlameInput resolve(CubeCalculationInput input, String characterClass) {
    boolean isWeapon = WEAPON_SLOT.equals(input.getPart());
    JobWeights weights = JobStatMapping.resolve(characterClass);

    int target =
        scoreResolver.calculate(
            input.getAddOptionStr(),
            input.getAddOptionDex(),
            input.getAddOptionInt(),
            input.getAddOptionLuk(),
            input.getAddOptionMaxHp(),
            input.getAddOptionAllStat(),
            input.getAddOptionAtt(),
            input.getAddOptionMag(),
            input.getAddOptionDmg(),
            input.getAddOptionBossDmg(),
            weights);

    if (target <= 0) {
      return null;
    }

    boolean isBossDrop = resolveBossDrop(input.getItemName(), isWeapon, characterClass);

    return new FlameInput(
        isBossDrop,
        isWeapon,
        target,
        input.getBaseAttackPower(),
        input.getBaseMagicPower(),
        weights);
  }

  private boolean resolveBossDrop(String itemName, boolean isWeapon, String characterClass) {
    if (BossEquipmentRegistry.isZeroWeapon(characterClass, isWeapon)) {
      return false;
    }
    return BossEquipmentRegistry.isBossEquipment(itemName, isWeapon);
  }

  /** 불꽃 계산에 필요한 해석된 입력 데이터 */
  public record FlameInput(
      boolean isBossDrop,
      boolean isWeapon,
      int target,
      int baseAtt,
      int baseMag,
      JobWeights weights) {}
}
