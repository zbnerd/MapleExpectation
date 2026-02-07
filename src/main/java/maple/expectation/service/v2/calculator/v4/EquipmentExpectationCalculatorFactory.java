package maple.expectation.service.v2.calculator.v4;

import lombok.RequiredArgsConstructor;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.dto.v4.EquipmentCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.calculator.v4.impl.*;
import maple.expectation.service.v2.policy.CubeCostPolicy;
import maple.expectation.service.v2.starforce.StarforceLookupTable;
import org.springframework.stereotype.Component;

/**
 * V4 장비 기대값 계산기 팩토리 (#240)
 *
 * <h3>역할</h3>
 *
 * <p>장비 입력을 기반으로 적절한 Decorator 체인을 구성합니다.
 *
 * <h3>Decorator 체인 예시</h3>
 *
 * <pre>
 * BaseEquipmentItem
 *   └→ BlackCubeDecoratorV4 (윗잠재)
 *       └→ RedCubeDecoratorV4 (윗잠재 보조) [선택적]
 *           └→ AdditionalCubeDecoratorV4 (아랫잠재)
 *               └→ StarforceDecoratorV4 (스타포스)
 * </pre>
 *
 * @see EquipmentExpectationCalculator 대상 인터페이스
 */
@Component
@RequiredArgsConstructor
public class EquipmentExpectationCalculatorFactory {

  private final CubeTrialsProvider trialsProvider;
  private final CubeCostPolicy costPolicy;
  private final StarforceLookupTable starforceLookupTable;

  /**
   * 전체 강화 계산기 생성 (블랙큐브 + 에디셔널 + 스타포스)
   *
   * @param input 장비 계산 입력
   * @return 전체 강화 계산기
   */
  public EquipmentExpectationCalculator createFullCalculator(EquipmentCalculationInput input) {
    // 1. 기본 아이템
    EquipmentExpectationCalculator calculator =
        new BaseEquipmentItem(input.getItemName(), input.getItemLevel(), input.getCurrentStar());

    // 2. 윗잠재 (블랙큐브) - 잠재능력이 있는 경우에만
    if (input.hasPotential()) {
      CubeCalculationInput potentialInput = input.toPotentialCubeInput();
      calculator = new BlackCubeDecoratorV4(calculator, trialsProvider, costPolicy, potentialInput);
    }

    // 3. 아랫잠재 (에디셔널큐브) - 에디셔널 잠재능력이 있는 경우에만
    if (input.hasAdditionalPotential()) {
      CubeCalculationInput additionalInput = input.toAdditionalCubeInput();
      calculator =
          new AdditionalCubeDecoratorV4(calculator, trialsProvider, costPolicy, additionalInput);
    }

    // 4. 스타포스 - 스타포스 정보가 있는 경우에만
    if (input.hasStarforce()) {
      calculator =
          new StarforceDecoratorV4(
              calculator,
              starforceLookupTable,
              input.getCurrentStar(),
              input.getTargetStar(),
              input.getItemLevel());
    }

    return calculator;
  }

  /**
   * 윗잠재(블랙큐브)만 계산하는 계산기 생성
   *
   * @param input 큐브 계산 입력
   * @return 블랙큐브 계산기
   */
  public EquipmentExpectationCalculator createBlackCubeCalculator(CubeCalculationInput input) {
    EquipmentExpectationCalculator calculator =
        new BaseEquipmentItem(
            input.getItemName(), input.getLevel(), 0 // 스타포스 정보 없음
            );
    return new BlackCubeDecoratorV4(calculator, trialsProvider, costPolicy, input);
  }

  /**
   * 아랫잠재(에디셔널큐브)만 계산하는 계산기 생성
   *
   * @param input 큐브 계산 입력
   * @return 에디셔널큐브 계산기
   */
  public EquipmentExpectationCalculator createAdditionalCubeCalculator(CubeCalculationInput input) {
    EquipmentExpectationCalculator calculator =
        new BaseEquipmentItem(input.getItemName(), input.getLevel(), 0);
    return new AdditionalCubeDecoratorV4(calculator, trialsProvider, costPolicy, input);
  }

  /**
   * 스타포스만 계산하는 계산기 생성
   *
   * @param itemName 아이템 이름
   * @param itemLevel 아이템 레벨
   * @param currentStar 현재 스타포스
   * @param targetStar 목표 스타포스
   * @return 스타포스 계산기
   */
  public EquipmentExpectationCalculator createStarforceCalculator(
      String itemName, int itemLevel, int currentStar, int targetStar) {
    EquipmentExpectationCalculator calculator =
        new BaseEquipmentItem(itemName, itemLevel, currentStar);
    return new StarforceDecoratorV4(
        calculator, starforceLookupTable, currentStar, targetStar, itemLevel);
  }
}
