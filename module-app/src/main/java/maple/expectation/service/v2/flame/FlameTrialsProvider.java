package maple.expectation.service.v2.flame;

import maple.expectation.core.domain.flame.FlameEquipCategory;
import maple.expectation.core.domain.flame.FlameType;
import maple.expectation.core.probability.FlameScoreCalculator;

/**
 * 환생의 불꽃 기대 시도 횟수 제공 인터페이스
 *
 * <h3>역할</h3>
 *
 * <p>장비 분류, 불꽃 종류, 직업 가중치 등을 기반으로 목표 환산치 도달까지의 기대 시도 횟수를 계산합니다.
 *
 * @see FlameTrialsService 기본 구현체
 * @see maple.expectation.service.v2.flame.component.FlameDpCalculator DP 기반 계산
 */
public interface FlameTrialsProvider {

  /**
   * 환생의 불꽃 기대 시도 횟수 계산
   *
   * @param category 장비 분류 (보스/비보스, 무기/방어구)
   * @param flameType 불꽃 종류 (강력한/영원한/심연의)
   * @param level 장비 레벨
   * @param weights 직업 가중치 (스케일 10 적용)
   * @param target 목표 환산치 (스케일 10 적용된 정수)
   * @param baseAtt 무기 기본 공격력 (무기 아닐 경우 0)
   * @param baseMag 무기 기본 마력 (무기 아닐 경우 0)
   * @return 기대 시도 횟수 (1/p), 불가능하면 null 또는 Infinity
   */
  Double calculateExpectedTrials(
      FlameEquipCategory category,
      FlameType flameType,
      int level,
      FlameScoreCalculator.JobWeights weights,
      int target,
      int baseAtt,
      int baseMag);
}
