package maple.expectation.dto.v4;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * V4 장비 기대값 응답 DTO (#240)
 *
 * <h3>V3 TotalExpectationResponse와의 차이</h3>
 * <ul>
 *   <li>비용 상세 분류: 블랙큐브, 레드큐브, 에디셔널, 스타포스 별도 제공</li>
 *   <li>프리셋별 데이터: 프리셋 1, 2, 3 각각의 기대값</li>
 *   <li>BigDecimal: 정밀 계산 결과 (long → BigDecimal)</li>
 *   <li>메타 정보: 계산 시점, 캐시 여부</li>
 * </ul>
 */
@Getter
@Builder
public class EquipmentExpectationResponseV4 {

    // ==================== 기본 정보 ====================

    private final String userIgn;
    private final LocalDateTime calculatedAt;
    private final boolean fromCache;

    // ==================== 총합 ====================

    private final BigDecimal totalExpectedCost;
    private final CostBreakdownDto totalCostBreakdown;

    // ==================== 프리셋별 기대값 ====================

    private final List<PresetExpectation> presets;

    // ==================== 내부 DTO ====================

    @Getter
    @Builder
    public static class PresetExpectation {
        private final int presetNo;
        private final BigDecimal totalExpectedCost;
        private final CostBreakdownDto costBreakdown;
        private final List<ItemExpectationV4> items;
    }

    @Getter
    @Builder
    public static class ItemExpectationV4 {
        private final String itemName;
        private final String itemPart;
        private final int itemLevel;
        private final BigDecimal expectedCost;
        private final CostBreakdownDto costBreakdown;
        private final String enhancePath;

        // 잠재능력 정보
        private final String potentialGrade;
        private final String additionalPotentialGrade;

        // 스타포스 정보
        private final int currentStar;
        private final int targetStar;
    }

    @Getter
    @Builder
    public static class CostBreakdownDto {
        private final BigDecimal blackCubeCost;
        private final BigDecimal redCubeCost;
        private final BigDecimal additionalCubeCost;
        private final BigDecimal starforceCost;

        public static CostBreakdownDto empty() {
            return CostBreakdownDto.builder()
                    .blackCubeCost(BigDecimal.ZERO)
                    .redCubeCost(BigDecimal.ZERO)
                    .additionalCubeCost(BigDecimal.ZERO)
                    .starforceCost(BigDecimal.ZERO)
                    .build();
        }

        public static CostBreakdownDto from(
                maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator.CostBreakdown breakdown) {
            return CostBreakdownDto.builder()
                    .blackCubeCost(breakdown.blackCubeCost())
                    .redCubeCost(breakdown.redCubeCost())
                    .additionalCubeCost(breakdown.additionalCubeCost())
                    .starforceCost(breakdown.starforceCost())
                    .build();
        }

        public CostBreakdownDto add(CostBreakdownDto other) {
            return CostBreakdownDto.builder()
                    .blackCubeCost(this.blackCubeCost.add(other.blackCubeCost))
                    .redCubeCost(this.redCubeCost.add(other.redCubeCost))
                    .additionalCubeCost(this.additionalCubeCost.add(other.additionalCubeCost))
                    .starforceCost(this.starforceCost.add(other.starforceCost))
                    .build();
        }
    }
}
