package maple.expectation.service.v2.calculator.impl;

import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.calculator.EnhanceDecorator;
import maple.expectation.service.v2.calculator.ExpectationCalculator;
import maple.expectation.service.v2.policy.CubeCostPolicy;

import java.util.Optional;

public class BlackCubeDecorator extends EnhanceDecorator {
    private final CubeTrialsProvider trialsProvider;
    private final CubeCostPolicy costPolicy;
    private final CubeCalculationInput input;
    private Long trials;

    public BlackCubeDecorator(ExpectationCalculator target,
                              CubeTrialsProvider trialsProvider,
                              CubeCostPolicy costPolicy,
                              CubeCalculationInput input) {
        super(target);
        this.trialsProvider = trialsProvider;
        this.costPolicy = costPolicy;
        this.input = input;
    }

    public Long calculateTrials() {
        trials = trialsProvider.calculateExpectedTrials(input, CubeType.BLACK).longValue();
        return trials;
    }

    @Override
    public Optional<Long> getTrials() {
        return Optional.of(trials);
    }

    @Override
    public long calculateCost() {
        // 1. 이전 단계 누적 비용 (BaseItem 등)
        long previousCost = super.calculateCost();

        // 2. 윗잠재(블랙큐브) 기대 시도 횟수 계산
        long expectedTrials = calculateTrials();

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