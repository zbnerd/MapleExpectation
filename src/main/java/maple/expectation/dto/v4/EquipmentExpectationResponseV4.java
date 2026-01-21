package maple.expectation.dto.v4;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

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
 *   <li>비용 텍스트: 조/억/만 단위 포맷</li>
 * </ul>
 *
 * <h3>PR #242: Jackson 역직렬화 지원</h3>
 * <p>{@code @Jacksonized}를 사용하여 {@code @Builder}와 Jackson 역직렬화를 호환시킵니다.
 * PER 캐시에서 Redis JSON을 역직렬화할 때 필수입니다.</p>
 */
@Getter
@Builder
@Jacksonized
public class EquipmentExpectationResponseV4 {

    // ==================== 기본 정보 ====================

    private final String userIgn;
    private final LocalDateTime calculatedAt;
    private final boolean fromCache;

    // ==================== 최대 기대값 프리셋 (#240 V4: 합산 → 최대값) ====================

    private final BigDecimal totalExpectedCost;      // 3개 프리셋 중 최대 기대값
    private final String totalCostText;              // "12조 3456억 7890만" (#240 V4)
    private final CostBreakdownDto totalCostBreakdown;
    private final int maxPresetNo;                   // 최대 기대값 프리셋 번호 (1, 2, 3)

    // ==================== 프리셋별 기대값 ====================

    private final List<PresetExpectation> presets;

    // ==================== 내부 DTO ====================

    @Getter
    @Builder
    @Jacksonized
    public static class PresetExpectation {
        private final int presetNo;
        private final BigDecimal totalExpectedCost;
        private final String totalCostText;      // "12조 3456억 7890만" (#240 V4)
        private final CostBreakdownDto costBreakdown;
        private final List<ItemExpectationV4> items;
    }

    @Getter
    @Builder
    @Jacksonized
    public static class ItemExpectationV4 {
        private final String itemName;
        private final String itemIcon;           // 아이콘 URL (#240 V4)
        private final String itemPart;
        private final int itemLevel;
        private final BigDecimal expectedCost;
        private final String expectedCostText;   // "500억" (#240 V4)
        private final CostBreakdownDto costBreakdown;
        private final String enhancePath;

        // 잠재능력 정보
        private final String potentialGrade;
        private final String additionalPotentialGrade;

        // 스타포스 정보
        private final int currentStar;
        private final int targetStar;
        private final boolean isNoljang;         // 놀장 여부 (#240 V4)

        // 큐브별 기대값 (#240 V4)
        private final CubeExpectationDto blackCubeExpectation;
        private final CubeExpectationDto additionalCubeExpectation;

        // 스타포스 옵션별 기대값 (#240)
        private final StarforceExpectationDto starforceExpectation;
    }

    /**
     * 큐브 기대값 DTO (#240 V4)
     *
     * <p>블랙큐브, 에디셔널큐브 각각의 기대값을 표현합니다.</p>
     */
    @Getter
    @Builder
    @Jacksonized
    public static class CubeExpectationDto {
        private final BigDecimal expectedCost;
        private final String expectedCostText;       // "5000억"
        private final BigDecimal expectedTrials;     // 기대 시도 횟수
        private final String currentGrade;           // 현재 등급 (UNIQUE 등)
        private final String targetGrade;            // 목표 등급 (LEGENDARY 등)
        private final String potential;              // 현재 잠재능력 텍스트 (#240 V4)

        public static CubeExpectationDto empty() {
            return CubeExpectationDto.builder()
                    .expectedCost(BigDecimal.ZERO)
                    .expectedCostText("0")
                    .expectedTrials(BigDecimal.ZERO)
                    .potential("")
                    .build();
        }
    }

    /**
     * 스타포스 기대값 DTO (#240)
     *
     * <p>ALL 파괴방지 O / ALL 파괴방지 X 두 가지 케이스 제공</p>
     */
    @Getter
    @Builder
    @Jacksonized
    public static class StarforceExpectationDto {

        // 현재/목표 스타 (#240 V4)
        private final int currentStar;
        private final int targetStar;
        private final boolean isNoljang;

        // 파괴방지 X 케이스 (기본)
        private final BigDecimal costWithoutDestroyPrevention;
        private final String costWithoutDestroyPreventionText;    // "500억" (#240 V4)
        private final BigDecimal expectedDestroyCountWithout;

        // 파괴방지 O 케이스 (15-17성 적용)
        private final BigDecimal costWithDestroyPrevention;
        private final String costWithDestroyPreventionText;       // "800억" (#240 V4)
        private final BigDecimal expectedDestroyCountWith;

        public static StarforceExpectationDto empty() {
            return StarforceExpectationDto.builder()
                    .currentStar(0)
                    .targetStar(0)
                    .isNoljang(false)
                    .costWithoutDestroyPrevention(BigDecimal.ZERO)
                    .costWithoutDestroyPreventionText("0")
                    .expectedDestroyCountWithout(BigDecimal.ZERO)
                    .costWithDestroyPrevention(BigDecimal.ZERO)
                    .costWithDestroyPreventionText("0")
                    .expectedDestroyCountWith(BigDecimal.ZERO)
                    .build();
        }
    }

    @Getter
    @Builder
    @Jacksonized
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
