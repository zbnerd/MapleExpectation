package maple.expectation.service.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.repository.CubeProbabilityRepository;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.global.error.exception.UnsupportedCalculationEngineException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.service.v2.calculator.CubeRateCalculator;
import maple.expectation.service.v2.cube.component.CubeDpCalculator;
import maple.expectation.service.v2.cube.component.DpModeInferrer;
import maple.expectation.service.v2.cube.config.CubeEngineFeatureFlag;
import maple.expectation.service.v2.impl.CubeServiceImpl;
import maple.expectation.util.StatType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CubeService Pure Unit Test Suite
 *
 * <p><b>Purpose:</b> Tests the cube trials calculation logic without any Spring dependencies.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Expected trials calculation for various cube configurations
 *   <li>DP mode inference and feature flag behavior
 *   <li>Error handling for unsupported calculation engine
 * </ul>
 *
 * <p><b>Pure Unit Test:</b> No Spring, no @SpringBootTest. Uses Mockito for dependency mocking.
 */
@Slf4j
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit: CubeService")
class CubeServiceTest {

  @Mock private CubeRateCalculator rateCalculator;

  @Mock private CubeDpCalculator dpCalculator;

  @Mock private CubeProbabilityRepository repository;

  @Mock private CubeEngineFeatureFlag featureFlag;

  @Mock private LogicExecutor executor;

  @Mock private DpModeInferrer dpModeInferrer;

  private CubeTrialsProvider cubeService;

  private static final String TABLE_VERSION = "2024-12-01";

  @BeforeEach
  void setUp() {
    cubeService =
        new CubeServiceImpl(
            rateCalculator, dpCalculator, repository, featureFlag, executor, dpModeInferrer);

    // Default mock behaviors
    lenient().when(repository.getCurrentTableVersion()).thenReturn(TABLE_VERSION);
    lenient().when(featureFlag.isDpEnabled()).thenReturn(true);
    lenient().when(featureFlag.isShadowEnabled()).thenReturn(false);
    lenient().when(dpModeInferrer.applyDpFields(any())).thenReturn(false);
  }

  @Test
  @DisplayName(
      "GIVEN: 200레벨 모자, STR 3줄(12%, 9%, 9%) 목표 WHEN: calculateExpectedTrials THEN: Returns positive trials")
  void given_strOptions_when_calculateExpectedTrials_shouldReturnPositiveValue() {
    // given
    CubeCalculationInput input =
        CubeCalculationInput.builder()
            .itemName("하이네스 워리어헬름")
            .part("모자")
            .level(200)
            .grade("레전드리")
            .options(List.of("STR +12%", "STR +9%", "STR +9%"))
            // Not in DP mode - targetStatType and minTotal are null
            .build();

    // Mock executor to return a calculated value
    Double expectedTrials = 350.0;
    when(executor.execute(any(), any(TaskContext.class))).thenReturn(expectedTrials);

    // when: Calculate expected trials
    Double result = cubeService.calculateExpectedTrials(input, CubeType.BLACK);

    // then
    assertThat(result).isGreaterThan(0);
    log.info("아이템: {}, 옵션: {}, 기대 횟수: {}", input.getItemName(), input.getOptions(), result);

    // Verify V1 engine was called (not DP mode)
    verify(executor).execute(any(), any(TaskContext.class));
  }

  @Test
  @DisplayName(
      "GIVEN: 쿨감 4초(-2초, -2초, 아무거나) 목표 WHEN: calculateExpectedTrials THEN: Returns positive trials")
  void given_cooldownOptions_when_calculateExpectedTrials_shouldReturnPositiveValue() {
    // given
    CubeCalculationInput input =
        CubeCalculationInput.builder()
            .itemName("에테르넬 나이트헬름")
            .part("모자")
            .level(250)
            .grade("레전드리")
            .options(Arrays.asList(null, "스킬 재사용 대기시간 -2초", "스킬 재사용 대기시간 -2초"))
            // Not in DP mode - targetStatType and minTotal are null
            .build();

    // Mock executor to return a calculated value
    Double expectedTrials = 500.0;
    when(executor.execute(any(), any(TaskContext.class))).thenReturn(expectedTrials);

    // when
    Double result = cubeService.calculateExpectedTrials(input, CubeType.BLACK);

    // then
    assertThat(result).isGreaterThan(0);
    log.info("목표: 쿨감 4초, 기대 횟수: {}", result);

    // Verify V1 engine was called
    verify(executor).execute(any(), any(TaskContext.class));
  }

  @Test
  @DisplayName(
      "GIVEN: DP 모드 요청 but dpEnabled=false WHEN: calculateExpectedTrials THEN: Throws UnsupportedCalculationEngineException")
  void
      given_dpModeRequest_butFeatureFlagDisabled_when_calculateExpectedTrials_shouldThrowException() {
    // given
    CubeCalculationInput input =
        CubeCalculationInput.builder()
            .itemName("테스트 아이템")
            .part("모자")
            .level(250)
            .grade("레전드리")
            .options(List.of("STR +12%", "STR +9%", "STR +9%"))
            // DP mode: targetStatType and minTotal are set
            .targetStatType(StatType.STR_PERCENT)
            .minTotal(21)
            .build();

    when(featureFlag.isDpEnabled()).thenReturn(false); // DP feature flag disabled

    // when & then
    assertThatThrownBy(() -> cubeService.calculateExpectedTrials(input, CubeType.BLACK))
        .isInstanceOf(UnsupportedCalculationEngineException.class);
  }

  @Test
  @DisplayName(
      "GIVEN: DP 모드 요청 and dpEnabled=true WHEN: calculateExpectedTrials THEN: Uses DP calculator")
  void
      given_dpModeRequest_andFeatureFlagEnabled_when_calculateExpectedTrials_shouldUseDpCalculator() {
    // given
    CubeCalculationInput input =
        CubeCalculationInput.builder()
            .itemName("테스트 아이템")
            .part("모자")
            .level(250)
            .grade("레전드리")
            .options(List.of("STR +12%", "STR +9%", "STR +9%"))
            // DP mode: targetStatType and minTotal are set
            .targetStatType(StatType.STR_PERCENT)
            .minTotal(21)
            .build();

    when(featureFlag.isDpEnabled()).thenReturn(true);

    Double expectedTrials = 250.0;
    when(executor.execute(any(), any(TaskContext.class))).thenReturn(expectedTrials);

    // when
    Double result = cubeService.calculateExpectedTrials(input, CubeType.BLACK);

    // then
    assertThat(result).isEqualTo(expectedTrials);
    verify(executor).execute(any(), any(TaskContext.class));
  }

  @Test
  @DisplayName(
      "GIVEN: Not ready input (options not ready) WHEN: calculateExpectedTrials THEN: Returns 0.0")
  void given_notReadyInput_when_calculateExpectedTrials_shouldReturnZero() {
    // given
    CubeCalculationInput input =
        CubeCalculationInput.builder()
            .itemName("테스트 아이템")
            .part("모자")
            .level(250)
            .grade("레전드리")
            .options(List.of()) // Empty options - not ready
            .build();

    // when
    Double result = cubeService.calculateExpectedTrials(input, CubeType.BLACK);

    // then
    assertThat(result).isEqualTo(0.0);
  }

  @Test
  @DisplayName(
      "GIVEN: Input suitable for DP auto-inference WHEN: calculateExpectedTrials THEN: Infers and uses DP")
  void given_dpAutoInferencePossible_when_calculateExpectedTrials_shouldInferAndUseDp() {
    // given
    CubeCalculationInput input =
        CubeCalculationInput.builder()
            .itemName("테스트 아이템")
            .part("모자")
            .level(250)
            .grade("레전드리")
            .options(List.of("STR +12%", "STR +9%", "STR +9%"))
            // Not explicitly DP mode (no targetStatType/minTotal initially)
            // But DpModeInferrer will add them
            .build();

    // Mock dpModeInferrer to actually populate the input with DP fields
    doAnswer(
            inv -> {
              CubeCalculationInput inputArg = inv.getArgument(0);
              // Use reflection or setter to populate targetStatType and minTotal
              // For test purposes, we'll use reflection to set the fields
              try {
                java.lang.reflect.Field targetStatTypeField =
                    CubeCalculationInput.class.getDeclaredField("targetStatType");
                targetStatTypeField.setAccessible(true);
                targetStatTypeField.set(inputArg, StatType.STR);

                java.lang.reflect.Field minTotalField =
                    CubeCalculationInput.class.getDeclaredField("minTotal");
                minTotalField.setAccessible(true);
                minTotalField.set(inputArg, 30); // Set minTotal
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              return true;
            })
        .when(dpModeInferrer)
        .applyDpFields(any());

    Double expectedTrials = 280.0;
    when(executor.execute(any(), any(TaskContext.class))).thenReturn(expectedTrials);

    // when
    Double result = cubeService.calculateExpectedTrials(input, CubeType.BLACK);

    // then
    assertThat(result).isEqualTo(expectedTrials);
    verify(dpModeInferrer).applyDpFields(input);
    verify(executor).execute(any(), any(TaskContext.class));
  }

  @Test
  @DisplayName("GIVEN: RED cube WHEN: calculateExpectedTrials THEN: Calculates correctly")
  void given_redCube_when_calculateExpectedTrials_shouldCalculateCorrectly() {
    // given
    CubeCalculationInput input =
        CubeCalculationInput.builder()
            .itemName("에테르넬 장갑")
            .part("장갑")
            .level(250)
            .grade("레전드리")
            .options(List.of("STR +12%", "STR +12%", "STR +12%"))
            .build();

    Double expectedTrials = 1000.0;
    when(executor.execute(any(), any(TaskContext.class))).thenReturn(expectedTrials);

    // when
    Double result = cubeService.calculateExpectedTrials(input, CubeType.RED);

    // then
    assertThat(result).isEqualTo(expectedTrials);
    verify(executor).execute(any(), any(TaskContext.class));
  }

  @Test
  @DisplayName("GIVEN: ADDITIONAL cube WHEN: calculateExpectedTrials THEN: Calculates correctly")
  void given_additionalCube_when_calculateExpectedTrials_shouldCalculateCorrectly() {
    // given
    CubeCalculationInput input =
        CubeCalculationInput.builder()
            .itemName("아케인심 Umbra 샤워")
            .part("샤워")
            .level(250)
            .grade("레전드리")
            .options(List.of("STR +12%", "STR +9%", "STR +9%"))
            .build();

    Double expectedTrials = 420.0;
    when(executor.execute(any(), any(TaskContext.class))).thenReturn(expectedTrials);

    // when
    Double result = cubeService.calculateExpectedTrials(input, CubeType.ADDITIONAL);

    // then
    assertThat(result).isEqualTo(expectedTrials);
    verify(executor).execute(any(), any(TaskContext.class));
  }
}
