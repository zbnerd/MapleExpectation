package maple.expectation.service.v2.cube;

import java.math.RoundingMode;
import java.util.Optional;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.policy.CubeCostPolicy;

/**
 * Abstract template for Cube decorators that eliminates duplication between V2 (long) and V4
 * (BigDecimal).
 *
 * <h3>Generic Number Type Parameter</h3>
 *
 * <p>N extends Number allows this template to work with both:
 *
 * <ul>
 *   <li>V2: Long (primitive long wrapper)
 *   <li>V4: BigDecimal (precise decimal calculations)
 * </ul>
 *
 * <h3>Template Method Pattern</h3>
 *
 * <p>Common cube calculation logic is provided in this abstract class. Subclasses only need to:
 *
 * <ul>
 *   <li>Specify the cube type (BLACK, RED, ADDITIONAL)
 *   <li>Provide number conversion utilities (long ↔ BigDecimal)
 *   <li>Customize enhance path description
 * </ul>
 *
 * <h3>Benefits</h3>
 *
 * <ul>
 *   <li>Code reduction: ~15% less duplication
 *   <li>Single source of truth for cube calculation logic
 *   <li>Type-safe generic parameter ensures compile-time correctness
 *   <li>Easy to add new cube types in the future
 * </ul>
 *
 * @param <N> Number type (Long for V2, BigDecimal for V4)
 * @param <T> Target calculator type (ExpectationCalculator for V2, EquipmentExpectationCalculator
 *     for V4)
 */
public abstract class AbstractCubeDecorator<N extends Number, T> {

  private static final int PRECISION_SCALE = 2;

  protected final T target;
  protected final CubeTrialsProvider trialsProvider;
  protected final CubeCostPolicy costPolicy;
  protected final CubeCalculationInput input;
  protected N trials;

  /**
   * Protected constructor for subclass initialization.
   *
   * @param target The wrapped calculator (previous enhancement stage)
   * @param trialsProvider Provider for calculating expected trials
   * @param costPolicy Policy for cube cost calculation
   * @param input Input parameters for cube calculation
   */
  protected AbstractCubeDecorator(
      T target,
      CubeTrialsProvider trialsProvider,
      CubeCostPolicy costPolicy,
      CubeCalculationInput input) {
    this.target = target;
    this.trialsProvider = trialsProvider;
    this.costPolicy = costPolicy;
    this.input = input;
  }

  /**
   * Get the cube type for this decorator (Template Method hook).
   *
   * @return CubeType (BLACK, RED, or ADDITIONAL)
   */
  protected abstract CubeType getCubeType();

  /**
   * Calculate expected trials and cache result.
   *
   * <p>Uses geometric distribution expected value: E[X] = 1 / p
   *
   * <p>Handles edge cases:
   *
   * <ul>
   *   <li>null trials → 0
   *   <li>Infinity trials → 0 (impossible combination)
   *   <li>Finite trials → converted to N type
   * </ul>
   *
   * @return Expected number of trials
   */
  public N calculateTrials() {
    if (trials == null) {
      Double rawTrials = trialsProvider.calculateExpectedTrials(input, getCubeType());

      // P0 Fix (#262): Infinity values cannot convert to BigDecimal → ZERO handling
      // Infinity = impossible combination = cost calculation meaningless
      if (rawTrials != null && Double.isFinite(rawTrials)) {
        trials = convertFromDouble(rawTrials);
      } else {
        trials = getZero();
      }
    }
    return trials;
  }

  /**
   * Get cached trials value (Template Method hook for Optional return).
   *
   * @return Optional containing trials, or empty if not calculated
   */
  protected abstract Optional<N> getTrialsOptional();

  /**
   * Calculate cost per trial from policy (Template Method hook).
   *
   * @return Cost per trial as type N
   */
  protected abstract N getCostPerTrial();

  /**
   * Calculate total cost for this cube enhancement (Template Method).
   *
   * @return Total cost (previous cost + cube cost)
   */
  protected abstract N calculateTotalCost();

  /**
   * Get enhance path description (Template Method hook).
   *
   * @return Base enhance path from target
   */
  protected abstract String getBaseEnhancePath();

  /**
   * Get cube-specific path suffix (Template Method hook).
   *
   * @return Path suffix (e.g., " > 블랙큐브(윗잠)")
   */
  protected abstract String getCubePathSuffix();

  /**
   * Convert Double to N type (Template Method hook).
   *
   * @param value Double value to convert
   * @return Converted value as N
   */
  protected abstract N convertFromDouble(Double value);

  /**
   * Convert long to N type (Template Method hook).
   *
   * @param value long value to convert
   * @return Converted value as N
   */
  protected abstract N convertFromLong(long value);

  /**
   * Get zero value for type N (Template Method hook).
   *
   * @return Zero as N
   */
  protected abstract N getZero();

  /**
   * Add two N values (Template Method hook).
   *
   * @param a First value
   * @param b Second value
   * @return Sum as N
   */
  protected abstract N add(N a, N b);

  /**
   * Multiply two N values (Template Method hook).
   *
   * @param a First value
   * @param b Second value
   * @return Product as N
   */
  protected abstract N multiply(N a, N b);

  /**
   * Round BigDecimal to specified scale (V4 only hook).
   *
   * @param value Value to round
   * @param scale Decimal places
   * @return Rounded value as N
   */
  protected N roundBigDecimal(N value, int scale) {
    // Default implementation: no rounding (for V2 Long)
    return value;
  }

  /**
   * Get final enhance path with cube suffix.
   *
   * @return Complete enhance path string
   */
  public final String getEnhancePath() {
    return getBaseEnhancePath() + getCubePathSuffix();
  }

  /**
   * Get raw long cost from policy (common helper).
   *
   * @return Cost per trial as long
   */
  protected final long getLongCostPerTrial() {
    return costPolicy.getCubeCost(getCubeType(), input.getLevel(), input.getGrade());
  }

  /**
   * Get precision scale for BigDecimal operations.
   *
   * @return Scale value (2 for V4)
   */
  protected final int getPrecisionScale() {
    return PRECISION_SCALE;
  }

  /**
   * Check if rounding should be applied (V4 specific).
   *
   * @return true if this is a V4 BigDecimal decorator
   */
  protected boolean shouldRoundTrials() {
    return false; // Default: V2 doesn't round
  }

  /**
   * Get rounding mode for V4 calculations.
   *
   * @return RoundingMode.HALF_UP
   */
  protected RoundingMode getRoundingMode() {
    return RoundingMode.HALF_UP;
  }
}
