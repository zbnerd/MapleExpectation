package maple.expectation.service.v2.calculator.v4.impl;

import maple.expectation.service.v2.calculator.v4.EquipmentEnhanceDecorator;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator;
import maple.expectation.service.v2.starforce.StarforceLookupTable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * V4 스타포스 데코레이터 (#240)
 *
 * <h3>스타포스 특성</h3>
 * <ul>
 *   <li>장비의 기본 스탯 강화 (0~25성)</li>
 *   <li>15성 이상부터 파괴 확률 존재</li>
 *   <li>세이프가드 옵션으로 파괴 방지 가능 (비용 2배)</li>
 * </ul>
 *
 * <h3>Lookup Table 사용</h3>
 * <p>StarforceLookupTable에서 pre-computed 기대값 조회 (O(1))</p>
 *
 * @see StarforceLookupTable 스타포스 기대값 조회
 * @see EquipmentEnhanceDecorator 추상 데코레이터
 */
public class StarforceDecoratorV4 extends EquipmentEnhanceDecorator {

    private static final int PRECISION_SCALE = 2;
    private static final int DEFAULT_TARGET_STAR = 22; // 일반적인 목표 스타포스

    private final StarforceLookupTable lookupTable;
    private final int currentStar;
    private final int targetStar;
    private final int itemLevel;
    private BigDecimal starforceCost;

    /**
     * 스타포스 데코레이터 생성
     *
     * @param target 이전 단계 계산기
     * @param lookupTable 스타포스 기대값 조회 테이블
     * @param currentStar 현재 스타포스 (0~25)
     * @param targetStar 목표 스타포스 (currentStar ~ 25)
     * @param itemLevel 아이템 레벨
     */
    public StarforceDecoratorV4(
            EquipmentExpectationCalculator target,
            StarforceLookupTable lookupTable,
            int currentStar,
            int targetStar,
            int itemLevel) {
        super(target);
        this.lookupTable = lookupTable;
        this.currentStar = currentStar;
        this.targetStar = targetStar;
        this.itemLevel = itemLevel;
    }

    /**
     * 기본 목표 스타포스(22성)로 생성
     */
    public StarforceDecoratorV4(
            EquipmentExpectationCalculator target,
            StarforceLookupTable lookupTable,
            int currentStar,
            int itemLevel) {
        this(target, lookupTable, currentStar, DEFAULT_TARGET_STAR, itemLevel);
    }

    @Override
    public BigDecimal calculateCost() {
        BigDecimal previousCost = super.calculateCost();
        BigDecimal sfCost = calculateStarforceCost();
        return previousCost.add(sfCost);
    }

    /**
     * 스타포스 기대 비용 계산
     *
     * <p>Lookup Table에서 pre-computed 값을 조회합니다.</p>
     *
     * @return 스타포스 기대 비용
     */
    public BigDecimal calculateStarforceCost() {
        if (starforceCost == null) {
            // 이미 목표 스타에 도달한 경우
            if (currentStar >= targetStar) {
                starforceCost = BigDecimal.ZERO;
            } else {
                // Lookup Table에서 기대값 조회
                starforceCost = lookupTable.getExpectedCost(currentStar, targetStar, itemLevel)
                        .setScale(PRECISION_SCALE, RoundingMode.HALF_UP);
            }
        }
        return starforceCost;
    }

    @Override
    public Optional<BigDecimal> getTrials() {
        // 스타포스는 단순 시도 횟수로 표현하기 어려움 (성공/실패/파괴 복합 확률)
        // 대신 calculateStarforceCost()를 사용하여 기대 비용 제공
        return Optional.empty();
    }

    @Override
    public CostBreakdown getDetailedCosts() {
        CostBreakdown base = super.getDetailedCosts();
        BigDecimal sfCost = calculateStarforceCost();
        return base.withStarforce(base.starforceCost().add(sfCost));
    }

    @Override
    public String getEnhancePath() {
        return super.getEnhancePath() +
                String.format(" > 스타포스(%d→%d성)", currentStar, targetStar);
    }

    public int getCurrentStar() {
        return currentStar;
    }

    public int getTargetStar() {
        return targetStar;
    }

    public int getItemLevel() {
        return itemLevel;
    }
}
