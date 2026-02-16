package maple.expectation.domain.service.calculator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import maple.expectation.domain.model.calculator.DensePmf;
import maple.expectation.domain.model.calculator.DiceRollProbability;
import maple.expectation.domain.model.calculator.SparsePmf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Characterization Test for ProbabilityConverter Domain Service.
 *
 * <p>This test captures the ACTUAL behavior of ProbabilityConverter to ensure domain extraction
 * maintains equivalence. Following ADR-017-S1 pattern.
 */
@DisplayName("ProbabilityConverter Domain Service - Characterization Test")
class ProbabilityConverterCharacterizationTest {

  // ==================== Test Suite 1: Factory Method ====================

  @Nested
  @DisplayName("Static Factory Method")
  class StaticFactoryMethod {

    @Test
    @DisplayName("Utility class should prevent instantiation")
    void utilityClass_shouldPreventInstantiation() {
      assertThrows(
          UnsupportedOperationException.class,
          () -> {
            var constructor = ProbabilityConverter.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            constructor.newInstance();
          },
          "ProbabilityConverter should not be instantiable");
    }
  }

  // ==================== Test Suite 2: DiceRollProbability Value Object ====================

  @Nested
  @DisplayName("DiceRollProbability Value Object")
  class DiceRollProbabilityValueObject {

    @Test
    @DisplayName("should create valid probability distribution")
    void shouldCreateValidDistribution() {
      int[] values = {0, 1, 2};
      double[] probs = {0.5, 0.3, 0.2};

      DiceRollProbability dist = new DiceRollProbability(values, probs);

      assertAll(
          "distribution",
          () -> assertEquals(3, dist.size()),
          () -> assertEquals(0, dist.valueAt(0)),
          () -> assertEquals(0.5, dist.probAt(0), 0.001),
          () -> assertEquals(2, dist.maxValue()));
    }

    @Test
    @DisplayName("should throw for null values array")
    void shouldThrowForNullValues() {
      double[] probs = {0.5, 0.5};

      assertThrows(
          IllegalArgumentException.class,
          () -> new DiceRollProbability(null, probs),
          "Should throw for null values");
    }

    @Test
    @DisplayName("should throw for null probabilities array")
    void shouldThrowForNullProbabilities() {
      int[] values = {0, 1};

      assertThrows(
          IllegalArgumentException.class,
          () -> new DiceRollProbability(values, null),
          "Should throw for null probabilities");
    }

    @Test
    @DisplayName("should throw for empty arrays")
    void shouldThrowForEmptyArrays() {
      assertThrows(
          IllegalArgumentException.class,
          () -> new DiceRollProbability(new int[0], new double[0]),
          "Should throw for empty arrays");
    }

    @Test
    @DisplayName("should throw for mismatched array lengths")
    void shouldThrowForMismatchedLengths() {
      assertThrows(
          IllegalArgumentException.class,
          () -> new DiceRollProbability(new int[2], new double[3]),
          "Should throw for mismatched lengths");
    }

    @Test
    @DisplayName("should throw for negative probability")
    void shouldThrowForNegativeProbability() {
      int[] values = {0, 1};
      double[] probs = {0.5, -0.5};

      assertThrows(
          IllegalArgumentException.class,
          () -> new DiceRollProbability(values, probs),
          "Should throw for negative probability");
    }

    @Test
    @DisplayName("should throw for negative value")
    void shouldThrowForNegativeValue() {
      int[] values = {0, -1};
      double[] probs = {0.5, 0.5};

      assertThrows(
          IllegalArgumentException.class,
          () -> new DiceRollProbability(values, probs),
          "Should throw for negative value");
    }

    @Test
    @DisplayName("should detect valid distribution")
    void shouldDetectValidDistribution() {
      int[] values = {0, 1};
      double[] probs = {0.5, 0.5};

      DiceRollProbability dist = new DiceRollProbability(values, probs);

      assertTrue(dist.isValidDistribution(), "Distribution summing to 1.0 should be valid");
    }

    @Test
    @DisplayName("should throw on index out of bounds")
    void shouldThrowOnIndexOutOfBounds() {
      int[] values = {0, 1};
      double[] probs = {0.5, 0.5};

      DiceRollProbability dist = new DiceRollProbability(values, probs);

      assertThrows(
          IndexOutOfBoundsException.class,
          () -> dist.valueAt(2),
          "Should throw for out of bounds index");
    }
  }

  // ==================== Test Suite 3: Convolution Algorithm ====================

  @Nested
  @DisplayName("Convolution Algorithm")
  class ConvolutionAlgorithm {

    @Test
    @DisplayName("convolveAll() should handle empty slot list")
    void convolveAll_shouldHandleEmptySlotList() {
      List<SparsePmf> slots = List.of();

      DensePmf result = ProbabilityConverter.convolveAll(slots, 10, false);

      assertEquals(1.0, result.massAt(0), 0.001, "p(0) should be 1.0 for no slots");
    }

    @Test
    @DisplayName("convolveAll() should validate null input")
    void convolveAll_shouldValidateNullInput() {
      assertThrows(
          IllegalArgumentException.class,
          () -> ProbabilityConverter.convolveAll(null, 10, false),
          "Should throw for null slots");
    }

    @Test
    @DisplayName("convolveAll() should validate negative target")
    void convolveAll_shouldValidateNegativeTarget() {
      List<SparsePmf> slots = List.of(createSimpleSlot(0, 1.0));

      assertThrows(
          IllegalArgumentException.class,
          () -> ProbabilityConverter.convolveAll(slots, -1, false),
          "Should throw for negative target");
    }

    @Test
    @DisplayName("convolveAll() should convolve single slot")
    void convolveAll_shouldConvolveSingleSlot() {
      // Single slot with deterministic outcome: always roll 1
      List<SparsePmf> slots = List.of(createSimpleSlot(1, 1.0));

      DensePmf result = ProbabilityConverter.convolveAll(slots, 10, false);

      assertEquals(1.0, result.massAt(1), 0.001, "p(1) should be 1.0");
      assertEquals(0.0, result.massAt(0), 0.001, "p(0) should be 0.0");
    }

    @Test
    @DisplayName("convolveAll() should apply tail clamp when enabled")
    void convolveAll_shouldApplyTailClamp() {
      // Slot with max value 10, but target is 5
      List<SparsePmf> slots = List.of(createSimpleSlot(10, 1.0));

      DensePmf result = ProbabilityConverter.convolveAll(slots, 5, true);

      // All probability should be accumulated into bucket 5
      assertEquals(1.0, result.massAt(5), 0.001, "p(5) should be 1.0 with tail clamp");
      assertEquals(0.0, result.massAt(10), 0.001, "p(10) should be 0.0 with tail clamp");
    }
  }

  // ==================== Helper Methods ====================

  private SparsePmf createSimpleSlot(int value, double probability) {
    return new SparsePmf(new int[] {value}, new double[] {probability});
  }
}
