package maple.expectation.service.v2.calculator.v4.impl;

import lombok.RequiredArgsConstructor;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * V4 기본 장비 아이템 (Decorator Pattern - Concrete Component) (#240)
 *
 * <h3>역할</h3>
 * <p>Decorator 체인의 시작점. 기본 아이템 자체의 비용은 0입니다.</p>
 *
 * @see EquipmentExpectationCalculator 대상 인터페이스
 */
@RequiredArgsConstructor
public class BaseEquipmentItem implements EquipmentExpectationCalculator {

    private final String itemName;
    private final int itemLevel;
    private final int currentStar;

    @Override
    public BigDecimal calculateCost() {
        return BigDecimal.ZERO; // 기본 아이템 자체의 비용은 0
    }

    @Override
    public String getEnhancePath() {
        return itemName;
    }

    @Override
    public Optional<BigDecimal> getTrials() {
        return Optional.of(BigDecimal.ZERO);
    }

    @Override
    public CostBreakdown getDetailedCosts() {
        return CostBreakdown.empty();
    }

    public int getItemLevel() {
        return itemLevel;
    }

    public int getCurrentStar() {
        return currentStar;
    }
}
