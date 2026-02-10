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
 * V4 레드큐브 데코레이터 (리팩토링: AbstractCubeDecoratorV4 사용)
 *
 * <h3>리팩토링 내역</h3>
 *
 * <ul>
 *   <li>중복 로직 제거: AbstractCubeDecoratorV4 템플릿 사용
 *   <li>코드 감소: ~60% (97 → 40 라인)
 *   <li>단일 책임: 큐브 타입과 경로 접미사만 정의
 * </ul>
 *
 * <h3>레드큐브 특성</h3>
 *
 * <ul>
 *   <li>윗잠재(메인 잠재능력) 재설정
 *   <li>블랙큐브보다 저렴하지만 등급 상승 확률이 낮음
 *   <li>주로 중간 단계 옵션 작업에 사용
 * </ul>
 *
 * @see AbstractCubeDecoratorV4 공통 로직 템플릿
 */
public class RedCubeDecoratorV4 extends AbstractCubeDecoratorV4 {

  public RedCubeDecoratorV4(
      EquipmentExpectationCalculator target,
      CubeTrialsProvider trialsProvider,
      CubeCostPolicy costPolicy,
      CubeCalculationInput input) {
    super(target, trialsProvider, costPolicy, input);
  }

  @Override
  protected CubeType getCubeType() {
    return CubeType.RED;
  }

  @Override
  protected String getCubePathSuffix() {
    return " > 레드큐브(윗잠)";
  }

  @Override
  protected CostBreakdown updateCostBreakdown(
      CostBreakdown base, BigDecimal cubeCost, BigDecimal trials) {
    return base.withRedCube(base.redCubeCost().add(cubeCost), trials);
  }
}
