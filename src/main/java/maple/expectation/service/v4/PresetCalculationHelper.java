package maple.expectation.service.v4;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import maple.expectation.domain.cost.CostFormatter;
import maple.expectation.domain.equipment.SecondaryWeaponCategory;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.dto.v4.EquipmentCalculationInput;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.CostBreakdownDto;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.CubeExpectationDto;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.FlameExpectationDto;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.ItemExpectationV4;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.StarforceExpectationDto;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculatorFactory;
import maple.expectation.service.v2.flame.FlameEquipCategory;
import maple.expectation.service.v2.flame.FlameInputResolver;
import maple.expectation.service.v2.flame.FlameInputResolver.FlameInput;
import maple.expectation.service.v2.flame.FlameTrialsProvider;
import maple.expectation.service.v2.flame.FlameType;
import maple.expectation.service.v2.flame.component.FlameScoreCalculator;
import maple.expectation.service.v2.starforce.StarforceLookupTable;
import maple.expectation.service.v2.starforce.config.NoljangProbabilityTable;
import org.springframework.stereotype.Component;

/**
 * V4 프리셋 계산 헬퍼 (EquipmentExpectationServiceV4에서 분리)
 *
 * <h3>책임: 개별 프리셋 + 아이템 기대값 계산</h3>
 *
 * <ul>
 *   <li>프리셋 기대값 계산 (calculatePreset)
 *   <li>개별 아이템 빌드 (buildInput, buildItemResult)
 *   <li>스타포스/큐브 기대값 계산 (calculateStarforceExpectation, buildCubeExpectation)
 * </ul>
 *
 * <h3>분해 근거</h3>
 *
 * <p>EquipmentExpectationServiceV4의 calculatePreset() 87줄을 각 20줄 이내의 6개 메서드로 분해하여 SRP 준수 (CLAUDE.md
 * Section 4)
 */
@Component
@RequiredArgsConstructor
public class PresetCalculationHelper {

  private final EquipmentExpectationCalculatorFactory calculatorFactory;
  private final StarforceLookupTable starforceLookupTable;
  private final FlameTrialsProvider flameTrialsProvider;
  private final FlameInputResolver flameInputResolver;

  /**
   * 프리셋 기대값 계산
   *
   * @param cubeInputs 프리셋의 큐브 입력 목록
   * @param presetNo 프리셋 번호 (1~3)
   * @param characterClass 직업명 (환생의 불꽃 동적 계산용)
   * @return 프리셋 기대값 결과
   */
  public PresetExpectation calculatePreset(
      List<CubeCalculationInput> cubeInputs, int presetNo, String characterClass) {
    List<ItemExpectationV4> itemResults = new ArrayList<>();
    BigDecimal totalCost = BigDecimal.ZERO;
    CostBreakdownDto totalBreakdown = CostBreakdownDto.empty();

    for (var cubeInput : cubeInputs) {
      if (!cubeInput.isReady()) {
        itemResults.add(buildNoPotentialItem(cubeInput, presetNo, characterClass));
        continue;
      }

      EquipmentCalculationInput input = buildInput(cubeInput, presetNo);
      ItemExpectationV4 itemResult = calculateSingleItem(input, cubeInput, characterClass);

      itemResults.add(itemResult);
      totalCost = totalCost.add(itemResult.getExpectedCost());
      totalBreakdown = totalBreakdown.add(itemResult.getCostBreakdown());
    }

    return PresetExpectation.builder()
        .presetNo(presetNo)
        .totalExpectedCost(totalCost)
        .totalCostText(CostFormatter.format(totalCost))
        .costBreakdown(totalBreakdown)
        .items(itemResults)
        .build();
  }

  /** 큐브 입력 → 계산 입력 변환 */
  EquipmentCalculationInput buildInput(CubeCalculationInput cubeInput, int presetNo) {
    boolean isNoljang = cubeInput.isNoljangEquipment();
    int parsedStarforce = cubeInput.getStarforce();
    int targetStar =
        isNoljang
            ? Math.min(parsedStarforce, NoljangProbabilityTable.MAX_NOLJANG_STAR)
            : parsedStarforce;

    String potentialPart =
        SecondaryWeaponCategory.resolvePotentialPart(
            cubeInput.getPart(), cubeInput.getItemEquipmentPart());

    return EquipmentCalculationInput.builder()
        .itemName(cubeInput.getItemName())
        .itemPart(potentialPart)
        .itemEquipmentPart(cubeInput.getItemEquipmentPart())
        .itemIcon(cubeInput.getItemIcon())
        .itemLevel(cubeInput.getLevel())
        .presetNo(presetNo)
        .isNoljang(isNoljang)
        .potentialGrade(cubeInput.getGrade())
        .potentialOptions(cubeInput.getOptions())
        .additionalPotentialGrade(cubeInput.getAdditionalGrade())
        .additionalPotentialOptions(cubeInput.getAdditionalOptions())
        .currentStar(0)
        .targetStar(targetStar)
        .build();
  }

  /** 단일 아이템 기대값 계산 */
  private ItemExpectationV4 calculateSingleItem(
      EquipmentCalculationInput input, CubeCalculationInput cubeInput, String characterClass) {
    EquipmentExpectationCalculator calculator = calculatorFactory.createFullCalculator(input);
    BigDecimal itemCost = calculator.calculateCost();
    var costBreakdown = calculator.getDetailedCosts();

    return buildItemResult(
        input, cubeInput, itemCost, costBreakdown, calculator.getEnhancePath(), characterClass);
  }

  /** 아이템 기대값 결과 빌드 */
  private ItemExpectationV4 buildItemResult(
      EquipmentCalculationInput input,
      CubeCalculationInput cubeInput,
      BigDecimal itemCost,
      EquipmentExpectationCalculator.CostBreakdown costBreakdown,
      String enhancePath,
      String characterClass) {
    StarforceExpectationDto starforceExpectation =
        calculateStarforceExpectation(
            input.getCurrentStar(), input.getTargetStar(), input.getItemLevel(), input.isNoljang());

    FlameExpectationDto flameExpectation =
        resolveFlameExpectation(cubeInput, characterClass, input.getItemLevel());

    String potentialText = formatPotentialOptions(input.getPotentialOptions());
    String additionalPotentialText = formatPotentialOptions(input.getAdditionalPotentialOptions());

    CubeExpectationDto blackCubeExpectation =
        buildCubeExpectation(
            costBreakdown.blackCubeCost(),
            costBreakdown.blackCubeTrials(),
            input.getPotentialGrade(),
            "LEGENDARY",
            potentialText);
    CubeExpectationDto additionalCubeExpectation =
        buildCubeExpectation(
            costBreakdown.additionalCubeCost(),
            costBreakdown.additionalCubeTrials(),
            input.getAdditionalPotentialGrade(),
            "LEGENDARY",
            additionalPotentialText);

    return ItemExpectationV4.builder()
        .itemName(input.getItemName())
        .itemIcon(input.getItemIcon())
        .itemPart(input.getItemPart())
        .itemLevel(input.getItemLevel())
        .expectedCost(itemCost)
        .expectedCostText(CostFormatter.format(itemCost))
        .costBreakdown(CostBreakdownDto.from(costBreakdown))
        .enhancePath(enhancePath)
        .potentialGrade(input.getPotentialGrade())
        .additionalPotentialGrade(input.getAdditionalPotentialGrade())
        .currentStar(input.getCurrentStar())
        .targetStar(input.getTargetStar())
        .isNoljang(input.isNoljang())
        .specialRingLevel(cubeInput.getSpecialRingLevel())
        .blackCubeExpectation(blackCubeExpectation)
        .additionalCubeExpectation(additionalCubeExpectation)
        .starforceExpectation(starforceExpectation)
        .flameExpectation(flameExpectation)
        .build();
  }

  /** 잠재능력 없는 아이템 빌드 */
  ItemExpectationV4 buildNoPotentialItem(
      CubeCalculationInput cubeInput, int presetNo, String characterClass) {
    FlameExpectationDto flameExpectation =
        resolveFlameExpectation(cubeInput, characterClass, cubeInput.getLevel());

    return ItemExpectationV4.builder()
        .itemName(cubeInput.getItemName())
        .itemIcon(cubeInput.getItemIcon())
        .itemPart(cubeInput.getPart())
        .itemLevel(cubeInput.getLevel())
        .expectedCost(BigDecimal.ZERO)
        .expectedCostText("0원")
        .costBreakdown(CostBreakdownDto.empty())
        .enhancePath("")
        .potentialGrade(null)
        .additionalPotentialGrade(null)
        .currentStar(0)
        .targetStar(cubeInput.getStarforce())
        .isNoljang(cubeInput.isNoljangEquipment())
        .specialRingLevel(cubeInput.getSpecialRingLevel())
        .blackCubeExpectation(CubeExpectationDto.empty())
        .additionalCubeExpectation(CubeExpectationDto.empty())
        .starforceExpectation(StarforceExpectationDto.empty())
        .flameExpectation(flameExpectation)
        .build();
  }

  /** 큐브 기대값 DTO 빌드 */
  CubeExpectationDto buildCubeExpectation(
      BigDecimal cost,
      BigDecimal trials,
      String currentGrade,
      String targetGrade,
      String potentialText) {
    if (cost == null || cost.compareTo(BigDecimal.ZERO) == 0) {
      return CubeExpectationDto.empty();
    }

    return CubeExpectationDto.builder()
        .expectedCost(cost)
        .expectedCostText(CostFormatter.format(cost))
        .expectedTrials(trials != null ? trials : BigDecimal.ZERO)
        .currentGrade(currentGrade)
        .targetGrade(targetGrade)
        .potential(potentialText)
        .build();
  }

  /** 스타포스 기대값 계산 */
  StarforceExpectationDto calculateStarforceExpectation(
      int currentStar, int targetStar, int itemLevel, boolean isNoljang) {
    if (isNoljang) {
      return calculateNoljangStarforce(currentStar, targetStar, itemLevel);
    }
    return calculateRegularStarforce(currentStar, targetStar, itemLevel);
  }

  private StarforceExpectationDto calculateNoljangStarforce(
      int currentStar, int targetStar, int itemLevel) {
    BigDecimal noljangCost =
        NoljangProbabilityTable.getExpectedCostFromStar(
            currentStar, targetStar, itemLevel, true, true);
    BigDecimal roundedCost = roundToNearest100(noljangCost);
    return StarforceExpectationDto.builder()
        .currentStar(currentStar)
        .targetStar(targetStar)
        .isNoljang(true)
        .costWithoutDestroyPrevention(roundedCost)
        .costWithoutDestroyPreventionText(CostFormatter.format(roundedCost))
        .expectedDestroyCountWithout(BigDecimal.ZERO)
        .costWithDestroyPrevention(roundedCost)
        .costWithDestroyPreventionText(CostFormatter.format(roundedCost))
        .expectedDestroyCountWith(BigDecimal.ZERO)
        .build();
  }

  private StarforceExpectationDto calculateRegularStarforce(
      int currentStar, int targetStar, int itemLevel) {
    BigDecimal costWithout =
        starforceLookupTable.getExpectedCost(
            currentStar, targetStar, itemLevel, true, true, true, false);
    BigDecimal destroyCountWithout =
        starforceLookupTable.getExpectedDestroyCount(currentStar, targetStar, true, true, false);

    BigDecimal costWith =
        starforceLookupTable.getExpectedCost(
            currentStar, targetStar, itemLevel, true, true, true, true);
    BigDecimal destroyCountWith =
        starforceLookupTable.getExpectedDestroyCount(currentStar, targetStar, true, true, true);

    BigDecimal roundedCostWithout = roundToNearest100(costWithout);
    BigDecimal roundedCostWith = roundToNearest100(costWith);

    return StarforceExpectationDto.builder()
        .currentStar(currentStar)
        .targetStar(targetStar)
        .isNoljang(false)
        .costWithoutDestroyPrevention(roundedCostWithout)
        .costWithoutDestroyPreventionText(CostFormatter.format(roundedCostWithout))
        .expectedDestroyCountWithout(destroyCountWithout)
        .costWithDestroyPrevention(roundedCostWith)
        .costWithDestroyPreventionText(CostFormatter.format(roundedCostWith))
        .expectedDestroyCountWith(destroyCountWith)
        .build();
  }

  /** 잠재능력 옵션 포맷팅 */
  String formatPotentialOptions(List<String> options) {
    if (options == null || options.isEmpty()) {
      return "";
    }
    return String.join(" | ", options);
  }

  /**
   * 불꽃 기대값 해석 및 계산 (#303 동적 불꽃 계산)
   *
   * <p>FlameInputResolver로 장비 데이터에서 동적으로 계산 입력을 추출하고, 추옵이 없는 장비는 empty를 반환합니다.
   */
  private FlameExpectationDto resolveFlameExpectation(
      CubeCalculationInput cubeInput, String characterClass, int level) {
    FlameInput flameInput = flameInputResolver.resolve(cubeInput, characterClass);
    if (flameInput == null) {
      return FlameExpectationDto.empty();
    }
    return calculateFlameExpectation(level, flameInput);
  }

  /**
   * 환생의 불꽃 기대값 계산
   *
   * <p>강력한/영원한/심연의 불꽃 3종에 대해 동일 목표 환산치로 기대 시도 횟수를 계산합니다.
   */
  private FlameExpectationDto calculateFlameExpectation(int level, FlameInput flameInput) {
    FlameEquipCategory category =
        FlameEquipCategory.of(flameInput.isBossDrop(), flameInput.isWeapon());
    FlameScoreCalculator.JobWeights weights = flameInput.weights();
    int target = flameInput.target();
    int baseAtt = flameInput.baseAtt();
    int baseMag = flameInput.baseMag();

    BigDecimal powerful =
        calculateFlameTrials(
            category, FlameType.POWERFUL, level, weights, target, baseAtt, baseMag);
    BigDecimal eternal =
        calculateFlameTrials(category, FlameType.ETERNAL, level, weights, target, baseAtt, baseMag);
    BigDecimal abyss =
        calculateFlameTrials(category, FlameType.ABYSS, level, weights, target, baseAtt, baseMag);

    return FlameExpectationDto.builder()
        .powerfulFlameTrials(powerful)
        .eternalFlameTrials(eternal)
        .abyssFlameTrials(abyss)
        .build();
  }

  private BigDecimal calculateFlameTrials(
      FlameEquipCategory category,
      FlameType flameType,
      int level,
      FlameScoreCalculator.JobWeights weights,
      int target,
      int baseAtt,
      int baseMag) {
    Double trials =
        flameTrialsProvider.calculateExpectedTrials(
            category, flameType, level, weights, target, baseAtt, baseMag);

    if (trials == null || !Double.isFinite(trials)) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(trials).setScale(2, RoundingMode.HALF_UP);
  }

  /** 100원 단위 반올림 */
  BigDecimal roundToNearest100(BigDecimal value) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    return value
        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100));
  }
}
