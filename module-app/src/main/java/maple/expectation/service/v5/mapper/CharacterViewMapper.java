package maple.expectation.service.v5.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import maple.expectation.dto.v5.EquipmentExpectationResponseV5;
import maple.expectation.dto.v5.EquipmentExpectationResponseV5.CostBreakdownDto;
import maple.expectation.dto.v5.EquipmentExpectationResponseV5.CubeExpectationDto;
import maple.expectation.dto.v5.EquipmentExpectationResponseV5.FlameExpectationDto;
import maple.expectation.dto.v5.EquipmentExpectationResponseV5.ItemExpectationV5;
import maple.expectation.dto.v5.EquipmentExpectationResponseV5.PresetExpectation;
import maple.expectation.dto.v5.EquipmentExpectationResponseV5.StarforceExpectationDto;
import maple.expectation.infrastructure.mongodb.CharacterValuationView;
import maple.expectation.infrastructure.mongodb.CharacterValuationView.CostBreakdownView;
import maple.expectation.infrastructure.mongodb.CharacterValuationView.ItemExpectationView;
import maple.expectation.infrastructure.mongodb.CharacterValuationView.PresetView;

/**
 * V5 CQRS: MongoDB View → V5 Response DTO Mapper
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Convert MongoDB CharacterValuationView to EquipmentExpectationResponseV5
 *   <li>Static utility class (SRP: Single Responsibility - mapping only)
 *   <li>Null-safe conversions with Optional
 * </ul>
 *
 * <h3>SOLID Compliance</h3>
 *
 * <ul>
 *   <li><b>SRP:</b> Only handles mapping logic
 *   <li><b>OCP:</b> Open for extension (private methods for sub-mappings)
 *   <li><b>ISP:</b> No fat interfaces (single public method)
 *   <li><b>DIP:</b> Depends on DTO interfaces, not concrete implementations
 * </ul>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CharacterViewMapper {

  /**
   * Convert MongoDB view to V5 response DTO
   *
   * @param view MongoDB character valuation view
   * @return V5 response DTO or empty if view is null
   */
  public static Optional<EquipmentExpectationResponseV5> toResponseDto(
      CharacterValuationView view) {
    if (view == null) {
      return Optional.empty();
    }

    // Convert Integer to Long for formatCostText
    Long totalCost =
        view.getTotalExpectedCost() != null ? view.getTotalExpectedCost().longValue() : 0L;

    return Optional.of(
        EquipmentExpectationResponseV5.builder()
            .userIgn(view.getUserIgn())
            .calculatedAt(view.getCalculatedAt())
            .fromCache(Optional.ofNullable(view.getFromCache()).orElse(true))
            .totalExpectedCost(
                view.getTotalExpectedCost() != null
                    ? BigDecimal.valueOf(view.getTotalExpectedCost())
                    : BigDecimal.ZERO)
            .totalCostText(formatCostText(totalCost))
            .totalCostBreakdown(CostBreakdownDto.empty()) // TODO: Compute from max preset
            .maxPresetNo(Optional.ofNullable(view.getMaxPresetNo()).orElse(1))
            .presets(toPresetDtos(view.getPresets()))
            .build());
  }

  // ==================== Private Helper Methods ====================

  private static List<PresetExpectation> toPresetDtos(List<PresetView> presets) {
    if (presets == null) {
      return List.of();
    }

    return presets.stream()
        .map(CharacterViewMapper::toPresetDto)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .toList();
  }

  private static Optional<PresetExpectation> toPresetDto(PresetView preset) {
    if (preset == null) {
      return Optional.empty();
    }

    return Optional.of(
        PresetExpectation.builder()
            .presetNo(preset.getPresetNo())
            .totalExpectedCost(
                preset.getTotalExpectedCost() != null
                    ? BigDecimal.valueOf(preset.getTotalExpectedCost())
                    : BigDecimal.ZERO)
            .totalCostText(preset.getTotalCostText())
            .costBreakdown(toCostBreakdownDto(preset.getCostBreakdown()))
            .items(toItemDtos(preset.getItems()))
            .build());
  }

  private static List<ItemExpectationV5> toItemDtos(List<ItemExpectationView> items) {
    if (items == null) {
      return List.of();
    }

    return items.stream()
        .map(CharacterViewMapper::toItemDto)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .toList();
  }

  private static Optional<ItemExpectationV5> toItemDto(ItemExpectationView item) {
    if (item == null) {
      return Optional.empty();
    }

    return Optional.of(
        ItemExpectationV5.builder()
            .itemName(item.getItemName())
            .expectedCost(
                item.getExpectedCost() != null
                    ? BigDecimal.valueOf(item.getExpectedCost())
                    : BigDecimal.ZERO)
            .expectedCostText(item.getCostText())
            .costBreakdown(CostBreakdownDto.empty())
            .enhancePath("")
            .potentialGrade("")
            .additionalPotentialGrade("")
            .currentStar(0)
            .targetStar(0)
            .isNoljang(false)
            .specialRingLevel(0)
            .blackCubeExpectation(CubeExpectationDto.empty())
            .additionalCubeExpectation(CubeExpectationDto.empty())
            .starforceExpectation(StarforceExpectationDto.empty())
            .flameExpectation(FlameExpectationDto.empty())
            .build());
  }

  private static CostBreakdownDto toCostBreakdownDto(CostBreakdownView breakdown) {
    if (breakdown == null) {
      return CostBreakdownDto.empty();
    }

    return CostBreakdownDto.builder()
        .blackCubeCost(
            breakdown.getBlackCubeCost() != null
                ? BigDecimal.valueOf(breakdown.getBlackCubeCost())
                : BigDecimal.ZERO)
        .redCubeCost(
            breakdown.getRedCubeCost() != null
                ? BigDecimal.valueOf(breakdown.getRedCubeCost())
                : BigDecimal.ZERO)
        .additionalCubeCost(
            breakdown.getAdditionalCubeCost() != null
                ? BigDecimal.valueOf(breakdown.getAdditionalCubeCost())
                : BigDecimal.ZERO)
        .starforceCost(
            breakdown.getStarforceCost() != null
                ? BigDecimal.valueOf(breakdown.getStarforceCost())
                : BigDecimal.ZERO)
        .flameCost(
            breakdown.getFlameCost() != null
                ? BigDecimal.valueOf(breakdown.getFlameCost())
                : BigDecimal.ZERO)
        .build();
  }

  private static String formatCostText(Long cost) {
    if (cost == null) {
      return "0";
    }

    long jo = cost / 1_0000_0000_0000L;
    long uk = (cost % 1_0000_0000_0000L) / 1_0000_0000L;
    long man = (cost % 1_0000_0000L) / 1_0000L;

    StringBuilder sb = new StringBuilder();
    if (jo > 0) {
      sb.append(jo).append("조 ");
    }
    if (uk > 0) {
      sb.append(uk).append("억 ");
    }
    if (man > 0 || sb.length() == 0) {
      sb.append(man).append("만");
    }

    return sb.toString().trim();
  }
}
