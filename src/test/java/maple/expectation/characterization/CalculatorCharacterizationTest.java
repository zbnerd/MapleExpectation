package maple.expectation.characterization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.service.v2.cube.component.ProbabilityConvolver;
import maple.expectation.service.v2.cube.dto.DensePmf;
import maple.expectation.service.v2.cube.dto.SparsePmf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 3 Characterization Tests: Calculator Domain
 *
 * <p><b>PURPOSE:</b> Capture CURRENT behavior of probability calculation before domain extraction.
 *
 * <p><b>NOTE:</b> These tests document WHAT the system DOES, not what it SHOULD do.
 * They serve as a safety net during refactoring to prevent unintended behavior changes.
 *
 * <h3>Target Classes (Phase 3 Calculator Domain):</h3>
 * <ul>
 *   <li>{@link ProbabilityConvolver} - DP convolution probability calculator
 *   <li>{@link DensePmf} - Dense Probability Mass Function
 *   <li>{@link SparsePmf} - Sparse Probability Mass Function
 * </ul>
 *
 * @see maple.expectation.service.v2.cube.component.ProbabilityConvolver
 * @see maple.expectation.service.v2.cube.dto.DensePmf
 * @see maple.expectation.service.v2.cube.dto.SparsePmf
 */
@Tag("characterization")
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 3: Calculator Domain Characterization Tests")
class CalculatorCharacterizationTest {

  @Mock private LogicExecutor executor;

  private ProbabilityConvolver convolver;

  private static final double TOLERANCE = 1e-12;

  @BeforeEach
  void setUp() {
    // LogicExecutor passthrough setup
    when(executor.execute(any(ThrowingSupplier.class), any(TaskContext.class)))
        .thenAnswer(
            invocation -> {
              ThrowingSupplier<?> supplier = invocation.getArgument(0);
              return supplier.get();
            });

    convolver = new ProbabilityConvolver(executor);
  }

  // ==================== ProbabilityConvolver Behavior ====================

  @Test
  @DisplayName("[CALC-001] Convolver: Single slot convolution returns original distribution")
  void convolver_single_slot_returns_original() {
    // Arrange
    SparsePmf slot = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3, 12, 0.2));
    int target = 12;

    // Act
    DensePmf result = convolver.convolveAll(List.of(slot), target, true);

    // Assert - Current Behavior
    assertThat(result.massAt(0)).isCloseTo(0.5, within(TOLERANCE));
    assertThat(result.massAt(6)).isCloseTo(0.3, within(TOLERANCE));
    assertThat(result.massAt(12)).isCloseTo(0.2, within(TOLERANCE));
    assertThat(result.totalMassKahan()).isCloseTo(1.0, within(TOLERANCE));
  }

  @Test
  @DisplayName("[CALC-002] Convolver: Two slot convolution produces correct PMF")
  void convolver_two_slot_produces_correct_pmf() {
    // Arrange
    SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
    SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
    int target = 18;

    // Act
    DensePmf result = convolver.convolveAll(List.of(slot1, slot2), target, true);

    // Assert - Current Behavior: Mass conservation
    assertThat(result.totalMassKahan()).isCloseTo(1.0, within(TOLERANCE));

    // Assert - Current Behavior: Key probabilities
    // P(sum=0) = 0.7 * 0.7 = 0.49
    assertThat(result.massAt(0)).isCloseTo(0.49, within(1e-10));

    // P(sum=6) = 0.7*0.2 + 0.2*0.7 = 0.28
    assertThat(result.massAt(6)).isCloseTo(0.28, within(1e-10));
  }

  @Test
  @DisplayName("[CALC-003] Convolver: Clamp ON limits array size to target + 1")
  void convolver_clamp_on_limits_array_size() {
    // Arrange
    SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
    SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
    int target = 10;

    // Act
    DensePmf result = convolver.convolveAll(List.of(slot1, slot2), target, true);

    // Assert - Current Behavior: Array size = target + 1
    assertThat(result.size()).isEqualTo(target + 1);
  }

  @Test
  @DisplayName("[CALC-004] Convolver: Clamp OFF allows array to grow to max sum")
  void convolver_clamp_off_allows_full_size() {
    // Arrange
    SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
    SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
    // Max sum = 9 + 9 = 18

    // Act
    DensePmf result = convolver.convolveAll(List.of(slot1, slot2), 100, false);

    // Assert - Current Behavior: Array size = maxSum + 1 = 19
    assertThat(result.size()).isEqualTo(19);
  }

  @Test
  @DisplayName("[CALC-005] Convolver: Target overflow accumulates in last bucket when clamped")
  void convolver_target_overflow_accumulates_in_last_bucket() {
    // Arrange
    SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.5, 100, 0.5)); // Max value 100
    SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.5, 100, 0.5));
    int target = 50; // Much smaller than max sum (200)

    // Act
    DensePmf result = convolver.convolveAll(List.of(slot1, slot2), target, true);

    // Assert - Current Behavior: Size limited to target + 1
    assertThat(result.size()).isEqualTo(51);

    // Assert - Current Behavior: Overflow accumulates in last bucket
    // P(sum >= 50) = P(sum=0,0) + P(sum=0,100) + P(sum=100,0) + P(sum=100,100)
    //                = 0.25 + 0.25 + 0.25 + 0.25 = 1.0
    assertThat(result.massAt(target)).isCloseTo(1.0, within(1e-10));
  }

  @Test
  @DisplayName("[CALC-006] Convolver: Throws on negative value in SparsePmf")
  void convolver_throws_on_negative_value() {
    // Arrange
    SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3, 12, 0.2));
    SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, -1, 0.3)); // Negative value

    // Act & Assert - Current Behavior
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> convolver.convolveAll(List.of(slot1, slot2), 20, true))
        .isInstanceOf(maple.expectation.global.error.exception.ProbabilityInvariantException.class)
        .hasMessageContaining("음수 contribution");
  }

  @Test
  @DisplayName("[CALC-007] Convolver: Three slot convolution maintains mass conservation")
  void convolver_three_slot_mass_conservation() {
    // Arrange
    SparsePmf slot1 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
    SparsePmf slot2 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));
    SparsePmf slot3 = SparsePmf.fromMap(Map.of(0, 0.7, 6, 0.2, 9, 0.1));

    // Act
    DensePmf result = convolver.convolveAll(List.of(slot1, slot2, slot3), 27, true);

    // Assert - Current Behavior: Mass conservation
    assertThat(result.totalMassKahan()).isCloseTo(1.0, within(TOLERANCE));
  }

  @Test
  @DisplayName("[CALC-008] Convolver: Empty slot list returns delta at 0")
  void convolver_empty_slot_list_returns_delta_at_zero() {
    // Arrange - Empty list
    List<SparsePmf> slots = List.of();
    int target = 10;

    // Act
    DensePmf result = convolver.convolveAll(slots, target, true);

    // Assert - Current Behavior: All mass at index 0
    assertThat(result.size()).isEqualTo(target + 1);
    assertThat(result.massAt(0)).isCloseTo(1.0, within(TOLERANCE));
    assertThat(result.totalMassKahan()).isCloseTo(1.0, within(TOLERANCE));
  }

  // ==================== DensePmf Behavior ====================

  @Test
  @DisplayName("[CALC-009] DensePmf: Canonical constructor clones input array")
  void densePmf_constructor_clones_input() {
    // Arrange
    double[] original = {0.1, 0.2, 0.3, 0.4};

    // Act
    DensePmf pmf = new DensePmf(original);
    original[0] = 0.999; // Modify original

    // Assert - Current Behavior: Internal array is cloned (defensive copy)
    assertThat(pmf.massAt(0)).isCloseTo(0.1, within(TOLERANCE));
  }

  @Test
  @DisplayName("[CALC-010] DensePmf: Accessor clones returned array")
  void densePmf_accessor_clones_output() {
    // Arrange
    DensePmf pmf = new DensePmf(new double[] {0.1, 0.2, 0.3});

    // Act
    double[] array = pmf.massByValue();
    array[0] = 0.999; // Modify returned array

    // Assert - Current Behavior: Returned array is cloned
    assertThat(pmf.massAt(0)).isCloseTo(0.1, within(TOLERANCE));
  }

  @Test
  @DisplayName("[CALC-011] DensePmf: massAt() returns 0 for out-of-bounds index")
  void densePmf_massAt_returns_zero_for_out_of_bounds() {
    // Arrange
    DensePmf pmf = DensePmf.fromArray(new double[] {0.1, 0.2, 0.3});

    // Act & Assert - Current Behavior
    assertThat(pmf.massAt(-1)).isEqualTo(0.0);
    assertThat(pmf.massAt(100)).isEqualTo(0.0);
  }

  @Test
  @DisplayName("[CALC-012] DensePmf: size() returns array length")
  void densePmf_size_returns_array_length() {
    // Arrange
    double[] array = {0.1, 0.2, 0.3, 0.4, 0.5};

    // Act
    DensePmf pmf = DensePmf.fromArray(array);

    // Assert - Current Behavior
    assertThat(pmf.size()).isEqualTo(array.length);
  }

  @Test
  @DisplayName("[CALC-013] DensePmf: totalMass() calculates simple sum")
  void densePmf_totalMass_calculates_simple_sum() {
    // Arrange
    DensePmf pmf = DensePmf.fromArray(new double[] {0.1, 0.2, 0.3, 0.4});

    // Act
    double totalMass = pmf.totalMass();

    // Assert - Current Behavior: Simple sum
    assertThat(totalMass).isCloseTo(1.0, within(1e-15));
  }

  @Test
  @DisplayName("[CALC-014] DensePmf: totalMassKahan() uses Kahan summation for precision")
  void densePmf_totalMassKahan_uses_kahan_summation() {
    // Arrange
    DensePmf pmf = DensePmf.fromArray(new double[] {0.1, 0.2, 0.3, 0.4});

    // Act
    double totalMass = pmf.totalMassKahan();

    // Assert - Current Behavior: More accurate than simple sum
    assertThat(totalMass).isCloseTo(1.0, within(TOLERANCE));
  }

  @Test
  @DisplayName("[CALC-015] DensePmf: hasNegative() detects negative probabilities")
  void densePmf_hasNegative_detects_negative() {
    // Arrange
    double[] array = {0.5, -0.1, 0.6}; // Contains negative

    // Act
    DensePmf pmf = DensePmf.fromArray(array);
    boolean hasNegative = pmf.hasNegative(0.0);

    // Assert - Current Behavior
    assertThat(hasNegative).isTrue();
  }

  @Test
  @DisplayName("[CALC-016] DensePmf: hasNegative() with tolerance ignores tiny negatives")
  void densePmf_hasNegative_with_tolerance_ignores_tiny_negatives() {
    // Arrange
    double[] array = {0.5, -1e-16, 0.5}; // Tiny negative (floating point error)

    // Act
    DensePmf pmf = DensePmf.fromArray(array);
    boolean hasNegative = pmf.hasNegative(-1e-15); // Tolerance

    // Assert - Current Behavior: Tiny negative ignored
    assertThat(hasNegative).isFalse();
  }

  @Test
  @DisplayName("[CALC-017] DensePmf: hasNaNOrInf() detects NaN values")
  void densePmf_hasNaNOrInf_detects_nan() {
    // Arrange
    double[] array = {0.5, Double.NaN, 0.5};

    // Act
    DensePmf pmf = DensePmf.fromArray(array);
    boolean hasNaN = pmf.hasNaNOrInf();

    // Assert - Current Behavior
    assertThat(hasNaN).isTrue();
  }

  @Test
  @DisplayName("[CALC-018] DensePmf: hasNaNOrInf() detects infinite values")
  void densePmf_hasNaNOrInf_detects_infinite() {
    // Arrange
    double[] array = {0.5, Double.POSITIVE_INFINITY, 0.5};

    // Act
    DensePmf pmf = DensePmf.fromArray(array);
    boolean hasInf = pmf.hasNaNOrInf();

    // Assert - Current Behavior
    assertThat(hasInf).isTrue();
  }

  @Test
  @DisplayName("[CALC-019] DensePmf: hasValueExceedingOne() detects probabilities > 1")
  void densePmf_hasValueExceedingOne_detects_excessive() {
    // Arrange
    double[] array = {0.5, 1.5, 0.5}; // 1.5 > 1.0

    // Act
    DensePmf pmf = DensePmf.fromArray(array);
    boolean exceedsOne = pmf.hasValueExceedingOne();

    // Assert - Current Behavior
    assertThat(exceedsOne).isTrue();
  }

  @Test
  @DisplayName("[CALC-020] DensePmf: hasValueExceedingOne() allows tiny epsilon over 1.0")
  void densePmf_hasValueExceedingOne_allows_epsilon() {
    // Arrange
    double[] array = {0.5, 1.0 + 1e-13, 0.5}; // 1.0 + 1e-13 (within EPS=1e-12)

    // Act
    DensePmf pmf = DensePmf.fromArray(array);
    boolean exceedsOne = pmf.hasValueExceedingOne();

    // Assert - Current Behavior: Tiny epsilon allowed
    assertThat(exceedsOne).isFalse();
  }

  // ==================== SparsePmf Behavior ====================

  @Test
  @DisplayName("[CALC-021] SparsePmf: fromMap creates correct distribution")
  void sparsePmf_fromMap_creates_distribution() {
    // Arrange
    Map<Integer, Double> map = Map.of(0, 0.5, 6, 0.3, 12, 0.2);

    // Act
    SparsePmf pmf = SparsePmf.fromMap(map);

    // Assert - Current Behavior
    assertThat(pmf.size()).isEqualTo(3);
    assertThat(pmf.valueAt(0)).isEqualTo(0);
    assertThat(pmf.probAt(0)).isCloseTo(0.5, within(TOLERANCE));
    assertThat(pmf.valueAt(1)).isEqualTo(6);
    assertThat(pmf.probAt(1)).isCloseTo(0.3, within(TOLERANCE));
  }

  @Test
  @DisplayName("[CALC-022] SparsePmf: maxValue() returns maximum value")
  void sparsePmf_maxValue_returns_maximum() {
    // Arrange
    Map<Integer, Double> map = Map.of(0, 0.5, 6, 0.3, 12, 0.2);

    // Act
    SparsePmf pmf = SparsePmf.fromMap(map);

    // Assert - Current Behavior
    assertThat(pmf.maxValue()).isEqualTo(12);
  }

  @Test
  @DisplayName("[CALC-023] SparsePmf: probAt() returns 0 for out-of-bounds index")
  void sparsePmf_probAt_returns_zero_for_out_of_bounds() {
    // Arrange
    SparsePmf pmf = SparsePmf.fromMap(Map.of(0, 0.5, 6, 0.3));

    // Act & Assert - Current Behavior
    assertThat(pmf.probAt(-1)).isEqualTo(0.0);
    assertThat(pmf.probAt(100)).isEqualTo(0.0);
  }

  @Test
  @DisplayName("[CALC-024] SparsePmf: size() returns number of entries")
  void sparsePmf_size_returns_entry_count() {
    // Arrange
    Map<Integer, Double> map = Map.of(0, 0.5, 6, 0.3, 9, 0.1, 12, 0.1);

    // Act
    SparsePmf pmf = SparsePmf.fromMap(map);

    // Assert - Current Behavior
    assertThat(pmf.size()).isEqualTo(4);
  }
}
