package maple.expectation.domain.service.calculator;

import java.util.List;
import maple.expectation.domain.model.calculator.DensePmf;
import maple.expectation.domain.model.calculator.SparsePmf;
import maple.expectation.error.exception.ProbabilityInvariantException;

/**
 * Domain Service for Probability Conversion.
 *
 * <p>This is a PURE domain service with NO Spring dependencies, NO infrastructure concerns. It
 * encapsulates business logic for converting sparse probability distributions to dense
 * distributions using dynamic programming (convolution).
 *
 * <p><b>Core Algorithm</b>: Slot-wise Convolution with Tail Clamping
 *
 * <ul>
 *   <li>Each slot (line) independently accumulates outcomes
 *   <li>Slots are independent (no cross-slot probability)
 *   <li>Same outcome may appear in multiple slots (cumulative probability)
 *   <li>Tail Clamp: probabilities beyond target are accumulated into target bucket
 * </ul>
 *
 * <h3>Design Principles</h3>
 *
 * <ul>
 *   <li><b>Static Methods</b>: All methods are static, no state
 *   <li><b>Validation</b>: All inputs are validated before processing
 *   <li><b>Clean Architecture</b>: Zero dependencies on infrastructure layer
 * </ul>
 *
 * <h3>Business Rules</h3>
 *
 * <ul>
 *   <li>Result PMF sum must be 1.0 ± 1e-12
 *   <li>No NaN or Inf values allowed
 *   <li>No negative probabilities allowed
 *   <li>No probabilities > 1.0 allowed
 * </ul>
 *
 * @see DiceRollProbability
 * @see SparsePmf
 * @see DensePmf
 */
public final class ProbabilityConverter {

  private static final double MASS_TOLERANCE = 1e-12;
  private static final double NEGATIVE_TOLERANCE = -1e-15;

  private ProbabilityConverter() {
    // Prevent instantiation
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Convolve multiple slot PMFs into single dense PMF.
   *
   * <p><b>Preconditions:</b>
   *
   * <ul>
   *   <li>Mass conservation: Σ=1 ± MASS_TOLERANCE
   *   <li>No NaN/Inf allowed
   *   <li>If enableTailClamp=true, max value = target+1
   * </ul>
   *
   * @param slotPmfs list of slot PMFs
   * @param target target sum (number of successful rolls)
   * @param enableTailClamp whether to enable tail clamping
   * @return convolved dense PMF
   * @throws ProbabilityInvariantException if invariant is violated
   */
  public static DensePmf convolveAll(
      List<SparsePmf> slotPmfs, int target, boolean enableTailClamp) {
    if (slotPmfs == null) {
      throw new IllegalArgumentException("slotPmfs cannot be null");
    }
    if (target < 0) {
      throw new IllegalArgumentException("target must be non-negative");
    }

    int maxIndex = enableTailClamp ? target : calculateMaxSum(slotPmfs);
    double[] acc = initializeAccumulator(maxIndex);

    for (SparsePmf slot : slotPmfs) {
      acc = convolveSlot(acc, slot, maxIndex);
    }

    DensePmf result = DensePmf.fromArray(acc);
    validateInvariants(result);
    return result;
  }

  /**
   * Initializes probability accumulator array.
   *
   * @param maxIndex maximum index to allocate
   * @return initialized accumulator with p(0)=1.0
   */
  private static double[] initializeAccumulator(int maxIndex) {
    double[] acc = new double[maxIndex + 1];
    acc[0] = 1.0; // Initial state: probability of sum=0 is 100%
    return acc;
  }

  /**
   * Convolves a single slot into the accumulator.
   *
   * @param acc current accumulator
   * @param slot slot PMF to convolve
   * @param maxIndex maximum index
   * @return updated accumulator
   */
  private static double[] convolveSlot(double[] acc, SparsePmf slot, int maxIndex) {
    double[] next = new double[maxIndex + 1];

    for (int i = 0; i <= maxIndex; i++) {
      if (acc[i] == 0.0) continue;
      accumulateSlotContributions(acc, slot, next, i, maxIndex);
    }

    return next;
  }

  /**
   * Accumulates slot contributions to next state.
   *
   * @param acc current accumulator
   * @param slot slot PMF
   * @param next next accumulator (output)
   * @param currentIndex current index being processed
   * @param maxIndex maximum index
   */
  private static void accumulateSlotContributions(
      double[] acc, SparsePmf slot, double[] next, int currentIndex, int maxIndex) {
    for (int k = 0; k < slot.size(); k++) {
      int value = slot.valueAt(k);
      double prob = slot.probAt(k);

      // P2 Fix (PR #159 Code refactoring): Guard against negative values
      // Prevents ArrayIndexOutOfBoundsException when parsing/extraction bugs occur
      if (value < 0) {
        throw new maple.expectation.error.exception.ProbabilityInvariantException(
            "Negative contribution detected: value=" + value + " (slot index=" + k + ")");
      }

      int targetIndex = Math.min(currentIndex + value, maxIndex); // Tail Clamp
      next[targetIndex] += acc[currentIndex] * prob;
    }
  }

  /**
   * Calculates maximum possible sum across all slots.
   *
   * @param slotPmfs list of slot PMFs
   * @return maximum sum value
   */
  private static int calculateMaxSum(List<SparsePmf> slotPmfs) {
    return slotPmfs.stream().mapToInt(SparsePmf::maxValue).sum();
  }

  /**
   * Validates DensePmf invariants using Kahan summation.
   *
   * @param pmf PMF to validate
   * @throws ProbabilityInvariantException if invariant is violated
   */
  private static void validateInvariants(DensePmf pmf) {
    double sum = pmf.totalMassKahan();
    if (Math.abs(sum - 1.0) > MASS_TOLERANCE) {
      throw new maple.expectation.error.exception.ProbabilityInvariantException(
          "Mass conservation violated: Σp=" + sum);
    }
    if (pmf.hasNegative(NEGATIVE_TOLERANCE)) {
      throw new maple.expectation.error.exception.ProbabilityInvariantException(
          "Negative probability detected");
    }
    if (pmf.hasNaNOrInf()) {
      throw new maple.expectation.error.exception.ProbabilityInvariantException("NaN/Inf detected");
    }
    if (pmf.hasValueExceedingOne()) {
      throw new maple.expectation.error.exception.ProbabilityInvariantException(
          "Probability > 1 detected");
    }
  }
}
