package maple.expectation.service.v2.calculator.v4.impl;

import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.calculator.v4.EquipmentEnhanceDecorator;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator;
import maple.expectation.service.v2.policy.CubeCostPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * V4 에디셔널큐브 데코레이터 (#240)
 *
 * <h3>에디셔널큐브 특성</h3>
 * <ul>
 *   <li>아랫잠재(에디셔널 잠재능력) 재설정</li>
 *   <li>에픽 → 유니크 → 레전드리 등급 상승</li>
 *   <li>메이플스토리: 에디셔널 옵션은 주력 스탯에 추가 버프 제공</li>
 * </ul>
 *
 * @see EquipmentEnhanceDecorator 추상 데코레이터
 */
public class AdditionalCubeDecoratorV4 extends EquipmentEnhanceDecorator {

    private static final int PRECISION_SCALE = 2;

    private final CubeTrialsProvider trialsProvider;
    private final CubeCostPolicy costPolicy;
    private final CubeCalculationInput input;
    private BigDecimal trials;

    public AdditionalCubeDecoratorV4(
            EquipmentExpectationCalculator target,
            CubeTrialsProvider trialsProvider,
            CubeCostPolicy costPolicy,
            CubeCalculationInput input) {
        super(target);
        this.trialsProvider = trialsProvider;
        this.costPolicy = costPolicy;
        this.input = input;
    }

    @Override
    public BigDecimal calculateCost() {
        BigDecimal previousCost = super.calculateCost();
        BigDecimal expectedTrials = calculateTrials();
        BigDecimal costPerTrial = BigDecimal.valueOf(
                costPolicy.getCubeCost(CubeType.ADDITIONAL, input.getLevel(), input.getGrade())
        );

        BigDecimal additionalCubeCost = expectedTrials.multiply(costPerTrial)
                .setScale(PRECISION_SCALE, RoundingMode.HALF_UP);

        return previousCost.add(additionalCubeCost);
    }

    public BigDecimal calculateTrials() {
        if (trials == null) {
            Double rawTrials = trialsProvider.calculateExpectedTrials(input, CubeType.ADDITIONAL);
            trials = rawTrials != null ? BigDecimal.valueOf(rawTrials) : BigDecimal.ZERO;
        }
        return trials;
    }

    @Override
    public Optional<BigDecimal> getTrials() {
        return Optional.of(calculateTrials());
    }

    @Override
    public CostBreakdown getDetailedCosts() {
        CostBreakdown base = super.getDetailedCosts();

        BigDecimal expectedTrials = calculateTrials();
        BigDecimal costPerTrial = BigDecimal.valueOf(
                costPolicy.getCubeCost(CubeType.ADDITIONAL, input.getLevel(), input.getGrade())
        );
        BigDecimal additionalCubeCost = expectedTrials.multiply(costPerTrial)
                .setScale(PRECISION_SCALE, RoundingMode.HALF_UP);

        return base.withAdditionalCube(base.additionalCubeCost().add(additionalCubeCost));
    }

    @Override
    public String getEnhancePath() {
        return super.getEnhancePath() + " > 에디셔널큐브(아랫잠)";
    }
}
