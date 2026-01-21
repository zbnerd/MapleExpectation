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
 * V4 레드큐브 데코레이터 (#240)
 *
 * <h3>레드큐브 특성</h3>
 * <ul>
 *   <li>윗잠재(메인 잠재능력) 재설정</li>
 *   <li>블랙큐브보다 저렴하지만 등급 상승 확률이 낮음</li>
 *   <li>주로 중간 단계 옵션 작업에 사용</li>
 * </ul>
 *
 * @see EquipmentEnhanceDecorator 추상 데코레이터
 */
public class RedCubeDecoratorV4 extends EquipmentEnhanceDecorator {

    private static final int PRECISION_SCALE = 2;

    private final CubeTrialsProvider trialsProvider;
    private final CubeCostPolicy costPolicy;
    private final CubeCalculationInput input;
    private BigDecimal trials;

    public RedCubeDecoratorV4(
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
                costPolicy.getCubeCost(CubeType.RED, input.getLevel(), input.getGrade())
        );

        BigDecimal redCubeCost = expectedTrials.multiply(costPerTrial)
                .setScale(PRECISION_SCALE, RoundingMode.HALF_UP);

        return previousCost.add(redCubeCost);
    }

    public BigDecimal calculateTrials() {
        if (trials == null) {
            Double rawTrials = trialsProvider.calculateExpectedTrials(input, CubeType.RED);
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
                costPolicy.getCubeCost(CubeType.RED, input.getLevel(), input.getGrade())
        );
        BigDecimal redCubeCost = expectedTrials.multiply(costPerTrial)
                .setScale(PRECISION_SCALE, RoundingMode.HALF_UP);

        return base.withRedCube(base.redCubeCost().add(redCubeCost));
    }

    @Override
    public String getEnhancePath() {
        return super.getEnhancePath() + " > 레드큐브(윗잠)";
    }
}
