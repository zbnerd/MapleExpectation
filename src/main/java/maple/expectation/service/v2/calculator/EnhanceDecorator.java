package maple.expectation.service.v2.calculator;

import lombok.RequiredArgsConstructor;

/** [Decorator] 다른 계산기를 감싸기 위한 추상 장식자 */
@RequiredArgsConstructor
public abstract class EnhanceDecorator implements ExpectationCalculator {
  protected final ExpectationCalculator target; // 래핑된 이전 단계 계산기

  @Override
  public long calculateCost() {
    return target.calculateCost();
  }

  @Override
  public String getEnhancePath() {
    return target.getEnhancePath();
  }
}
