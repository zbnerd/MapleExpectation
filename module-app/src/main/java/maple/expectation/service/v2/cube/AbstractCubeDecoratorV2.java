package maple.expectation.service.v2.cube;

import java.util.Optional;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import maple.expectation.service.v2.calculator.EnhanceDecorator;
import maple.expectation.service.v2.calculator.ExpectationCalculator;
import maple.expectation.service.v2.policy.CubeCostPolicy;

/**
 * V2-specific abstract cube decorator using Long type.
 *
 * <p>Extends AbstractCubeDecorator with V2-specific implementations:
 *
 * <ul>
 *   <li>Type parameter: Long (boxed long)
 *   <li>No rounding (exact integer arithmetic)
 *   <li>Extends EnhanceDecorator for V2 calculator chain
 * </ul>
 *
 * <h3>Usage Example</h3>
 *
 * <pre>{@code
 * public class BlackCubeDecorator extends AbstractCubeDecoratorV2 {
 *   public BlackCubeDecorator(
 *       ExpectationCalculator target,
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
public abstract class AbstractCubeDecoratorV2 extends EnhanceDecorator {

  private final AbstractCubeDecorator<Long, ExpectationCalculator> delegate;

  /**
   * Constructor that initializes both the decorator chain and the generic delegate.
   *
   * @param target The wrapped calculator (previous enhancement stage)
   * @param trialsProvider Provider for calculating expected trials
   * @param costPolicy Policy for cube cost calculation
   * @param input Input parameters for cube calculation
   */
  protected AbstractCubeDecoratorV2(
      ExpectationCalculator target,
      CubeTrialsProvider trialsProvider,
      CubeCostPolicy costPolicy,
      CubeCalculationInput input) {
    super(target);

    // Create delegate with V2-specific implementations
    this.delegate =
        new AbstractCubeDecorator<>(target, trialsProvider, costPolicy, input) {
          @Override
          protected CubeType getCubeType() {
            return AbstractCubeDecoratorV2.this.getCubeType();
          }

          @Override
          protected Optional<Long> getTrialsOptional() {
            return AbstractCubeDecoratorV2.this.getTrials();
          }

          @Override
          protected Long getCostPerTrial() {
            return AbstractCubeDecoratorV2.this.getCostPerTrial();
          }

          @Override
          protected Long calculateTotalCost() {
            return AbstractCubeDecoratorV2.this.calculateCost();
          }

          @Override
          protected String getBaseEnhancePath() {
            return AbstractCubeDecoratorV2.this.getBaseEnhancePath();
          }

          @Override
          protected String getCubePathSuffix() {
            return AbstractCubeDecoratorV2.this.getCubePathSuffix();
          }

          @Override
          protected Long convertFromDouble(Double value) {
            return value.longValue();
          }

          @Override
          protected Long convertFromLong(long value) {
            return value;
          }

          @Override
          protected Long getZero() {
            return 0L;
          }

          @Override
          protected Long add(Long a, Long b) {
            return a + b;
          }

          @Override
          protected Long multiply(Long a, Long b) {
            return a * b;
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
   * @return Expected number of trials (long)
   */
  public Long calculateTrials() {
    return delegate.calculateTrials();
  }

  /**
   * Get trials as Optional.
   *
   * @return Optional containing trials
   */
  @Override
  public Optional<Long> getTrials() {
    return Optional.of(delegate.calculateTrials());
  }

  /**
   * Calculate total cost: previous cost + (trials × cost per trial).
   *
   * @return Total cost (long)
   */
  @Override
  public long calculateCost() {
    // 1. Previous stage cumulative cost
    long previousCost = super.calculateCost();

    // 2. Expected trials for cube
    long expectedTrials = delegate.calculateTrials();

    // 3. Cost per trial from policy
    long costPerTrial = delegate.getLongCostPerTrial();

    // 4. Total cost = previous + (trials × costPerTrial)
    return previousCost + (expectedTrials * costPerTrial);
  }

  /**
   * Get cost per trial as Long.
   *
   * @return Cost per trial
   */
  protected Long getCostPerTrial() {
    return delegate.getLongCostPerTrial();
  }

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
}
