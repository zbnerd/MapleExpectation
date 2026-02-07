package maple.expectation.service.v2.calculator.impl;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import maple.expectation.service.v2.calculator.ExpectationCalculator;

/** [Concrete Component] 강화가 시작되는 원본 아이템 */
@RequiredArgsConstructor
public class BaseItem implements ExpectationCalculator {
  private final String itemName;

  @Override
  public long calculateCost() {
    return 0; // 기본 아이템 자체의 소모 비용은 0
  }

  @Override
  public String getEnhancePath() {
    return itemName;
  }

  @Override
  public Optional<Long> getTrials() {
    return Optional.of(0L);
  }
}
