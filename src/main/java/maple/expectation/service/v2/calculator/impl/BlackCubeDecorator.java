package maple.expectation.service.v2.calculator.impl;

import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeService;
import maple.expectation.service.v2.calculator.EnhanceDecorator;
import maple.expectation.service.v2.calculator.ExpectationCalculator;
import maple.expectation.service.v2.policy.CubeCostPolicy;

public class BlackCubeDecorator extends EnhanceDecorator {
    private final CubeService cubeService;
    private final CubeCostPolicy costPolicy;
    private final CubeCalculationInput input;

    public BlackCubeDecorator(ExpectationCalculator target,
                              CubeService cubeService,
                              CubeCostPolicy costPolicy,
                              CubeCalculationInput input) {
        super(target);
        this.cubeService = cubeService;
        this.costPolicy = costPolicy;
        this.input = input;
    }

    @Override
    public long calculateCost() {
        // 1. 이전 단계 누적 비용 (BaseItem 등)
        long previousCost = super.calculateCost();

        // 2. 윗잠재(블랙큐브) 기대 시도 횟수 계산
        long expectedTrials = cubeService.calculateExpectedTrials(input, CubeType.BLACK);

        // 3. 수정된 통합 비용 정책 적용 (CubeType.BLACK 전달)
        // 기존 getBlackCubeCost -> getCubeCost로 변경
        long costPerTrial = costPolicy.getCubeCost(CubeType.BLACK, input.getLevel(), input.getGrade());

        return previousCost + (expectedTrials * costPerTrial);
    }

    @Override
    public String getEnhancePath() {
        return super.getEnhancePath() + " > 블랙큐브(윗잠)";
    }
}