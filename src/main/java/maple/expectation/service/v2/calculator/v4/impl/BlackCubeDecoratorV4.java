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
 * V4 블랙큐브 데코레이터 (#240)
 *
 * <h3>5-Agent Council 합의사항</h3>
 * <ul>
 *   <li>🟣 Purple (Auditor): BigDecimal 필수 - truncation 방지</li>
 *   <li>RoundingMode.HALF_UP 명시적 사용</li>
 * </ul>
 *
 * <h3>블랙큐브 특성</h3>
 * <ul>
 *   <li>윗잠재(메인 잠재능력) 재설정</li>
 *   <li>레어 → 에픽 → 유니크 → 레전드리 등급 상승</li>
 *   <li>큐브 가격: 레벨 × 등급 계수</li>
 * </ul>
 *
 * @see EquipmentEnhanceDecorator 추상 데코레이터
 */
public class BlackCubeDecoratorV4 extends EquipmentEnhanceDecorator {

    private static final int PRECISION_SCALE = 2;

    private final CubeTrialsProvider trialsProvider;
    private final CubeCostPolicy costPolicy;
    private final CubeCalculationInput input;
    private BigDecimal trials;

    public BlackCubeDecoratorV4(
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
        // 1. 이전 단계 누적 비용
        BigDecimal previousCost = super.calculateCost();

        // 2. 블랙큐브 기대 시도 횟수 계산
        BigDecimal expectedTrials = calculateTrials();

        // 3. 단가 조회 (BigDecimal)
        BigDecimal costPerTrial = BigDecimal.valueOf(
                costPolicy.getCubeCost(CubeType.BLACK, input.getLevel(), input.getGrade())
        );

        // 4. 블랙큐브 총 비용 = 시도 횟수 × 단가
        BigDecimal blackCubeCost = expectedTrials.multiply(costPerTrial)
                .setScale(PRECISION_SCALE, RoundingMode.HALF_UP);

        return previousCost.add(blackCubeCost);
    }

    /**
     * 블랙큐브 기대 시도 횟수 계산
     *
     * <p>기하분포 기대값 = 1 / 성공확률</p>
     *
     * @return 기대 시도 횟수
     */
    public BigDecimal calculateTrials() {
        if (trials == null) {
            Double rawTrials = trialsProvider.calculateExpectedTrials(input, CubeType.BLACK);
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
                costPolicy.getCubeCost(CubeType.BLACK, input.getLevel(), input.getGrade())
        );
        BigDecimal blackCubeCost = expectedTrials.multiply(costPerTrial)
                .setScale(PRECISION_SCALE, RoundingMode.HALF_UP);

        return base.withBlackCube(base.blackCubeCost().add(blackCubeCost));
    }

    @Override
    public String getEnhancePath() {
        return super.getEnhancePath() + " > 블랙큐브(윗잠)";
    }
}
