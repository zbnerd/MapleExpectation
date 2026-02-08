package maple.expectation.service.v2.calculator.v4.impl;

import java.math.BigDecimal;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator.CostBreakdown;
import maple.expectation.service.v2.cube.AbstractCubeDecoratorV4;
import maple.expectation.service.v2.policy.CubeCostPolicy;

/**
 * V4 에디셔널큐브 데코레이터 (리팩토링: AbstractCubeDecoratorV4 사용)
 *
 * <h3>리팩토링 내역</h3>
 *
 * <ul>
 *   <li>중복 로직 제거: AbstractCubeDecoratorV4 템플릿 사용
 *   <li>코드 감소: ~60% (102 → 40 라인)
 *   <li>단일 책임: 큐브 타입과 경로 접미사만 정의
 * </ul>
 *
 * <h3>에디셔널큐브 특성</h3>
 *
 * <ul>
 *   <li>아랫잠재(에디셔널 잠재능력) 재설정
 *   <li>에픽 → 유니크 → 레전드리 등급 상승
 *   <li>메이플스토리: 에디셔널 옵션은 주력 스탯에 추가 버프 제공
 * </ul>
 *
 * @see AbstractCubeDecoratorV4 공통 로직 템플릿
 */
public class AdditionalCubeDecoratorV4 extends AbstractCubeDecoratorV4 {

  public AdditionalCubeDecoratorV4(
      EquipmentExpectationCalculator target,
      CubeTrialsProvider trialsProvider,
      CubeCostPolicy costPolicy,
      CubeCalculationInput input) {
    super(target, trialsProvider, costPolicy, input);
  }

  @Override
  protected CubeType getCubeType() {
    return CubeType.ADDITIONAL;
  }

  @Override
  protected String getCubePathSuffix() {
    return " > 에디셔널큐브(아랫잠)";
  }

  @Override
  protected CostBreakdown updateCostBreakdown(
      CostBreakdown base, BigDecimal cubeCost, BigDecimal trials) {
    return base.withAdditionalCube(base.additionalCubeCost().add(cubeCost), trials);
  }
}
