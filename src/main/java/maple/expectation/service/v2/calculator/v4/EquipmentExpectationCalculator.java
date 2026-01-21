package maple.expectation.service.v2.calculator.v4;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * V4 장비 기대값 계산기 인터페이스 (#240)
 *
 * <h3>5-Agent Council 합의사항</h3>
 * <ul>
 *   <li>🟣 Purple (Auditor): BigDecimal 필수 - 정밀 계산</li>
 *   <li>🔵 Blue (Architect): OCP 준수 - 기존 ExpectationCalculator 유지</li>
 * </ul>
 *
 * <h3>기존 ExpectationCalculator와의 차이</h3>
 * <ul>
 *   <li>calculateCost() → BigDecimal (long에서 변경)</li>
 *   <li>getTrials() → BigDecimal (정밀 기대값 계산)</li>
 *   <li>새로운 메서드: getDetailedCosts() - 비용 상세 분류</li>
 * </ul>
 *
 * @see maple.expectation.service.v2.calculator.ExpectationCalculator 기존 V2/V3 인터페이스
 */
public interface EquipmentExpectationCalculator {

    /**
     * 최종 소모 비용 합산 (BigDecimal)
     *
     * <p>Purple Agent 요구사항: 정밀 계산을 위해 BigDecimal 사용</p>
     *
     * @return 기대 비용 (메소 단위)
     */
    BigDecimal calculateCost();

    /**
     * 적용된 강화 경로 문자열 반환
     *
     * @return 강화 경로 (예: "무기 > 블랙큐브(윗잠) > 레드큐브(윗잠) > 에디셔널(아랫잠) > 스타포스")
     */
    String getEnhancePath();

    /**
     * 기대 시도 횟수 (기하분포 기반)
     *
     * @return 기대 시도 횟수 (없으면 Optional.empty())
     */
    Optional<BigDecimal> getTrials();

    /**
     * 비용 상세 분류
     *
     * <p>V4 API에서 항목별 비용 분류를 위해 사용</p>
     *
     * @return 비용 상세 (블랙큐브, 레드큐브, 에디셔널, 스타포스 등)
     */
    CostBreakdown getDetailedCosts();

    /**
     * 비용 상세 분류 Record
     */
    record CostBreakdown(
            BigDecimal blackCubeCost,
            BigDecimal redCubeCost,
            BigDecimal additionalCubeCost,
            BigDecimal starforceCost
    ) {
        public static CostBreakdown empty() {
            return new CostBreakdown(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO
            );
        }

        public BigDecimal total() {
            return blackCubeCost
                    .add(redCubeCost)
                    .add(additionalCubeCost)
                    .add(starforceCost);
        }

        public CostBreakdown withBlackCube(BigDecimal cost) {
            return new CostBreakdown(cost, redCubeCost, additionalCubeCost, starforceCost);
        }

        public CostBreakdown withRedCube(BigDecimal cost) {
            return new CostBreakdown(blackCubeCost, cost, additionalCubeCost, starforceCost);
        }

        public CostBreakdown withAdditionalCube(BigDecimal cost) {
            return new CostBreakdown(blackCubeCost, redCubeCost, cost, starforceCost);
        }

        public CostBreakdown withStarforce(BigDecimal cost) {
            return new CostBreakdown(blackCubeCost, redCubeCost, additionalCubeCost, cost);
        }
    }
}
