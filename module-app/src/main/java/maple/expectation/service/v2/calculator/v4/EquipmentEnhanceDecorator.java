package maple.expectation.service.v2.calculator.v4;

import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * V4 장비 강화 데코레이터 추상 클래스 (#240)
 *
 * <h3>Decorator Pattern (GoF)</h3>
 *
 * <p>기존 ExpectationCalculator를 감싸서 추가 강화 비용을 누적합니다.
 *
 * <h3>SOLID - OCP 준수</h3>
 *
 * <p>새로운 강화 타입 추가 시 이 클래스를 상속하여 확장
 *
 * @see EquipmentExpectationCalculator 대상 인터페이스
 */
@RequiredArgsConstructor
public abstract class EquipmentEnhanceDecorator implements EquipmentExpectationCalculator {

  protected final EquipmentExpectationCalculator target;

  @Override
  public BigDecimal calculateCost() {
    return target.calculateCost();
  }

  @Override
  public String getEnhancePath() {
    return target.getEnhancePath();
  }

  @Override
  public Optional<BigDecimal> getTrials() {
    return target.getTrials();
  }

  @Override
  public CostBreakdown getDetailedCosts() {
    return target.getDetailedCosts();
  }
}
