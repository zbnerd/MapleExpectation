package maple.expectation.service.v2.calculator.impl;

import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.calculator.ExpectationCalculator;
import maple.expectation.service.v2.cube.AbstractCubeDecoratorV2;
import maple.expectation.service.v2.policy.CubeCostPolicy;

/**
 * V2 블랙큐브 데코레이터 (리팩토링: AbstractCubeDecoratorV2 사용)
 *
 * <h3>리팩토링 내역</h3>
 *
 * <ul>
 *   <li>중복 로직 제거: AbstractCubeDecoratorV2 템플릿 사용
 *   <li>코드 감소: ~50% (58 → 30 라인)
 *   <li>단일 책임: 큐브 타입과 경로 접미사만 정의
 * </ul>
 *
 * <h3>블랙큐브 특성</h3>
 *
 * <ul>
 *   <li>윗잠재(메인 잠재능력) 재설정
 *   <li>레어 → 에픽 → 유니크 → 레전드리 등급 상승
 *   <li>큐브 가격: 레벨 × 등급 계수
 * </ul>
 *
 * @see AbstractCubeDecoratorV2 공통 로직 템플릿
 */
public class BlackCubeDecorator extends AbstractCubeDecoratorV2 {

  public BlackCubeDecorator(
      ExpectationCalculator target,
      CubeTrialsProvider trialsProvider,
      CubeCostPolicy costPolicy,
      CubeCalculationInput input) {
    super(target, trialsProvider, costPolicy, input);
  }

  @Override
  protected CubeType getCubeType() {
    return CubeType.BLACK;
  }

  @Override
  protected String getCubePathSuffix() {
    return " > 블랙큐브(윗잠)";
  }
}
