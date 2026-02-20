package maple.expectation.dto.v5;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * V5 CQRS 장비 기대값 응답 DTO
 *
 * <h3>V4와의 차이</h3>
 *
 * <ul>
 *   <li><b>CQRS Query Side:</b> MongoDB CharacterValuationView에서 조회
 *   <li><b>Cache-First:</b> MongoDB 히트 시 1-10ms 응답
 *   <li><b>Eventual Consistency:</b> calculatedAt 기준 TTL 24시간
 *   <li><b>fromCache:</b> MongoDB 조회 여부 표시
 * </ul>
 *
 * <h3>응답 시나리오</h3>
 *
 * <ol>
 *   <li><b>MongoDB HIT:</b> 200 OK + JSON 응답 (1-10ms)
 *   <li><b>MongoDB MISS:</b> 202 Accepted + 계산 큐 등록
 * </ol>
 */
@Getter
@Builder
@Jacksonized
public class EquipmentExpectationResponseV5 {

  // ==================== 기본 정보 ====================

  private final String userIgn;
  private final Instant calculatedAt;
  private final boolean fromCache;

  // ==================== 최대 기대값 프리셋 ====================

  private final BigDecimal totalExpectedCost; // 3개 프리셋 중 최대 기대값
  private final String totalCostText; // "12조 3456억 7890만"
  private final CostBreakdownDto totalCostBreakdown;
  private final int maxPresetNo; // 최대 기대값 프리셋 번호 (1, 2, 3)

  // ==================== 프리셋별 기대값 ====================

  private final List<PresetExpectation> presets;

  // ==================== 내부 DTO ====================

  @Getter
  @Builder
  @Jacksonized
  public static class PresetExpectation {
    private final int presetNo;
    private final BigDecimal totalExpectedCost;
    private final String totalCostText;
    private final CostBreakdownDto costBreakdown;
    private final List<ItemExpectationV5> items;
  }

  @Getter
  @Builder
  @Jacksonized
  public static class ItemExpectationV5 {
    private final String itemName;
    private final String itemIcon;
    private final String itemPart;
    private final int itemLevel;
    private final BigDecimal expectedCost;
    private final String expectedCostText;
    private final CostBreakdownDto costBreakdown;
    private final String enhancePath;

    // 잠재능력 정보
    private final String potentialGrade;
    private final String additionalPotentialGrade;

    // 스타포스 정보
    private final int currentStar;
    private final int targetStar;
    private final boolean isNoljang;

    // 특수 스킬 반지 레벨
    private final int specialRingLevel;

    // 큐브별 기대값
    private final CubeExpectationDto blackCubeExpectation;
    private final CubeExpectationDto additionalCubeExpectation;

    // 스타포스 옵션별 기대값
    private final StarforceExpectationDto starforceExpectation;

    // 환생의 불꽃 기대값
    private final FlameExpectationDto flameExpectation;
  }

  @Getter
  @Builder
  @Jacksonized
  public static class CubeExpectationDto {
    private final BigDecimal expectedCost;
    private final String expectedCostText;
    private final BigDecimal expectedTrials;
    private final String currentGrade;
    private final String targetGrade;
    private final String potential;

    public static CubeExpectationDto empty() {
      return CubeExpectationDto.builder()
          .expectedCost(BigDecimal.ZERO)
          .expectedCostText("0")
          .expectedTrials(BigDecimal.ZERO)
          .potential("")
          .build();
    }
  }

  @Getter
  @Builder
  @Jacksonized
  public static class StarforceExpectationDto {
    private final int currentStar;
    private final int targetStar;
    private final boolean isNoljang;
    private final BigDecimal costWithoutDestroyPrevention;
    private final String costWithoutDestroyPreventionText;
    private final BigDecimal expectedDestroyCountWithout;
    private final BigDecimal costWithDestroyPrevention;
    private final String costWithDestroyPreventionText;
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
  public static class FlameExpectationDto {
    private final BigDecimal powerfulFlameTrials;
    private final BigDecimal eternalFlameTrials;
    private final BigDecimal abyssFlameTrials;

    public static FlameExpectationDto empty() {
      return FlameExpectationDto.builder()
          .powerfulFlameTrials(BigDecimal.ZERO)
          .eternalFlameTrials(BigDecimal.ZERO)
          .abyssFlameTrials(BigDecimal.ZERO)
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
    private final BigDecimal flameCost;

    public static CostBreakdownDto empty() {
      return CostBreakdownDto.builder()
          .blackCubeCost(BigDecimal.ZERO)
          .redCubeCost(BigDecimal.ZERO)
          .additionalCubeCost(BigDecimal.ZERO)
          .starforceCost(BigDecimal.ZERO)
          .flameCost(BigDecimal.ZERO)
          .build();
    }
  }
}
