package maple.expectation.service.v2.cube;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.calculator.v4.EquipmentEnhanceDecorator;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator;
import maple.expectation.service.v2.calculator.v4.EquipmentExpectationCalculator.CostBreakdown;
import maple.expectation.service.v2.policy.CubeCostPolicy;

/**
 * V4-specific abstract cube decorator using BigDecimal type.
 *
 * <p>Extends AbstractCubeDecorator with V4-specific implementations:
 *
 * <ul>
 *   <li>Type parameter: BigDecimal (precise decimal arithmetic)
 *   <li>Rounds trials to integer (HALF_UP) before cost calculation
 *   <li>Extends EquipmentEnhanceDecorator for V4 calculator chain
 *   <li>Supports CostBreakdown for detailed cost tracking
 * </ul>
 *
 * <h3>V4 Improvements over V2</h3>
 *
 * <ul>
 *   <li>Precision: BigDecimal prevents floating-point truncation
 *   <li>Rounding: Trials rounded to integer before multiplication
 *   <li>Detailed breakdown: Separate tracking of cube costs and trials
 * </ul>
 *
 * <h3>Usage Example</h3>
 *
 * <pre>{@code
 * public class BlackCubeDecoratorV4 extends AbstractCubeDecoratorV4 {
 *   public BlackCubeDecoratorV4(
 *       EquipmentExpectationCalculator target,
 *       CubeTrialsProvider trialsProvider,
 *       CubeCostPolicy costPolicy,
 *       CubeCalculationInput input) {
 *     super(target, trialsProvider, costPolicy, input);
 *   }
 *
 *   @Override
 *   protected CubeType getCubeType() {
 *     return CubeType.BLACK;
 *   }
 *
 *   @Override
 *   protected String getCubePathSuffix() {
 *     return " > 블랙큐브(윗잠)";
 *   }
 * }
 * }</pre>
 */
public abstract class AbstractCubeDecoratorV4 extends EquipmentEnhanceDecorator {

  private static final int PRECISION_SCALE = 2;

  private final AbstractCubeDecorator<BigDecimal, EquipmentExpectationCalculator> delegate;

  /**
   * Constructor that initializes both the decorator chain and the generic delegate.
   *
   * @param target The wrapped calculator (previous enhancement stage)
   * @param trialsProvider Provider for calculating expected trials
   * @param costPolicy Policy for cube cost calculation
   * @param input Input parameters for cube calculation
   */
  protected AbstractCubeDecoratorV4(
      EquipmentExpectationCalculator target,
      CubeTrialsProvider trialsProvider,
      CubeCostPolicy costPolicy,
      CubeCalculationInput input) {
    super(target);

    // Create delegate with V4-specific implementations
    this.delegate =
        new AbstractCubeDecorator<BigDecimal, EquipmentExpectationCalculator>(
            target, trialsProvider, costPolicy, input) {

          @Override
          protected CubeType getCubeType() {
            return AbstractCubeDecoratorV4.this.getCubeType();
          }

          @Override
          protected Optional<BigDecimal> getTrialsOptional() {
            return AbstractCubeDecoratorV4.this.getTrials();
          }

          @Override
          protected BigDecimal getCostPerTrial() {
            return BigDecimal.valueOf(
                costPolicy.getCubeCost(getCubeType(), input.getLevel(), input.getGrade()));
          }

          @Override
          protected BigDecimal calculateTotalCost() {
            return AbstractCubeDecoratorV4.this.calculateCost();
          }

          @Override
          protected String getBaseEnhancePath() {
            return AbstractCubeDecoratorV4.this.getBaseEnhancePath();
          }

          @Override
          protected String getCubePathSuffix() {
            return AbstractCubeDecoratorV4.this.getCubePathSuffix();
          }

          @Override
          protected BigDecimal convertFromDouble(Double value) {
            return BigDecimal.valueOf(value);
          }

          @Override
          protected BigDecimal convertFromLong(long value) {
            return BigDecimal.valueOf(value);
          }

          @Override
          protected BigDecimal getZero() {
            return BigDecimal.ZERO;
          }

          @Override
          protected BigDecimal add(BigDecimal a, BigDecimal b) {
            return a.add(b);
          }

          @Override
          protected BigDecimal multiply(BigDecimal a, BigDecimal b) {
            return a.multiply(b);
          }

          @Override
          protected boolean shouldRoundTrials() {
            return true; // V4 always rounds trials
          }
        };
  }

  /**
   * Get the cube type for this decorator (subclass must implement).
   *
   * @return CubeType (BLACK, RED, or ADDITIONAL)
   */
  protected abstract CubeType getCubeType();

  /**
   * Get cube-specific path suffix (subclass must implement).
   *
   * @return Path suffix (e.g., " > 블랙큐브(윗잠)")
   */
  protected abstract String getCubePathSuffix();

  /**
   * Calculate expected trials using delegate.
   *
   * @return Expected number of trials (BigDecimal)
   */
  public BigDecimal calculateTrials() {
    return delegate.calculateTrials();
  }

  /**
   * Get trials as Optional.
   *
   * @return Optional containing trials
   */
  @Override
  public Optional<BigDecimal> getTrials() {
    return Optional.of(delegate.calculateTrials());
  }

  /**
   * Calculate total cost: previous cost + (rounded trials × cost per trial).
   *
   * <p>V4 improvement: Trials are rounded to integer before cost calculation.
   *
   * @return Total cost (BigDecimal)
   */
  @Override
  public BigDecimal calculateCost() {
    // 1. Previous stage cumulative cost
    BigDecimal previousCost = super.calculateCost();

    // 2. Expected trials for cube
    BigDecimal expectedTrials = delegate.calculateTrials();

    // 3. Round trials to integer (V4 improvement)
    BigDecimal roundedTrials = expectedTrials.setScale(0, RoundingMode.HALF_UP);

    // 4. Cost per trial from policy
    BigDecimal costPerTrial = BigDecimal.valueOf(delegate.getLongCostPerTrial());

    // 5. Total cost = previous + (roundedTrials × costPerTrial)
    BigDecimal cubeCost = roundedTrials.multiply(costPerTrial);

    return previousCost.add(cubeCost);
  }

  /**
   * Get detailed cost breakdown with cube-specific costs.
   *
   * <p>Subclasses should override this to add cube-specific cost breakdown.
   *
   * @return CostBreakdown with cube costs added
   */
  @Override
  public CostBreakdown getDetailedCosts() {
    CostBreakdown base = super.getDetailedCosts();

    // Calculate trials and cost
    BigDecimal expectedTrials = delegate.calculateTrials();
    BigDecimal roundedTrials = expectedTrials.setScale(0, RoundingMode.HALF_UP);
    BigDecimal costPerTrial = BigDecimal.valueOf(delegate.getLongCostPerTrial());
    BigDecimal cubeCost = roundedTrials.multiply(costPerTrial);

    // Delegate to subclass for specific CostBreakdown method
    return updateCostBreakdown(base, cubeCost, roundedTrials);
  }

  /**
   * Update CostBreakdown with cube-specific costs (Template Method hook).
   *
   * <p>Subclasses must implement this to call the appropriate CostBreakdown method:
   *
   * <ul>
   *   <li>Black Cube: base.withBlackCube(cost, trials)
   *   <li>Red Cube: base.withRedCube(cost, trials)
   *   <li>Additional Cube: base.withAdditionalCube(cost, trials)
   * </ul>
   *
   * @param base Base CostBreakdown from previous stage
   * @param cubeCost Cost for this cube
   * @param trials Rounded trials for this cube
   * @return Updated CostBreakdown
   */
  protected abstract CostBreakdown updateCostBreakdown(
      CostBreakdown base, BigDecimal cubeCost, BigDecimal trials);

  /**
   * Get base enhance path from target.
   *
   * @return Base enhance path
   */
  protected String getBaseEnhancePath() {
    return super.getEnhancePath();
  }

  /**
   * Get complete enhance path with cube suffix.
   *
   * @return Complete enhance path string
   */
  @Override
  public String getEnhancePath() {
    return delegate.getEnhancePath();
  }

  /**
   * Get precision scale for BigDecimal operations.
   *
   * @return Scale value (2)
   */
  protected int getPrecisionScale() {
    return PRECISION_SCALE;
  }
}
