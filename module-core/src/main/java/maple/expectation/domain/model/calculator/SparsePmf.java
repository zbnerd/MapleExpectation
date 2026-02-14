package maple.expectation.domain.model.calculator;

import java.util.List;
import java.util.Map;

/**
 * Sparse Probability Mass Function (Sparse PMF)
 *
 * <p>Purpose: Slot-specific distribution (few non-zero entries, small K)
 *
 * <p>Immutable - guaranteed by defensive copying
 *
 * <h3>Core Assumptions</h3>
 *
 * <ul>
 *   <li>Each slot (line) draws options independently
 *   <li>Draws between slots are independent (not conditional probability)
 *   <li>Same option may appear in multiple slots
 * </ul>
 *
 * <h3>SOLID Compliance</h3>
 *
 * <ul>
 *   <li>SRP: Data representation only, validation logic separated to PmfValidator
 *   <li>DIP: Depends on validation interface, implementation independent
 * </ul>
 *
 * <h3>P0: Immutability Guarantee</h3>
 *
 * <ul>
 *   <li>Defensive copying in canonical constructor
 *   <li>Defensive copying in accessors
 * </ul>
 */
public record SparsePmf(int[] values, double[] probs) {

  /** P0: Canonical constructor defensive copying */
  public SparsePmf(int[] values, double[] probs) {
    this.values = values != null ? values.clone() : new int[0];
    this.probs = probs != null ? probs.clone() : new double[0];
  }

  /** P0: Accessor defensive copying (values) */
  @Override
  public int[] values() {
    return values.clone();
  }

  /** P0: Accessor defensive copying (probs) */
  @Override
  public double[] probs() {
    return probs.clone();
  }

  /**
   * Create SparsePmf from Map (sorted by value)
   *
   * <p>Note: Constructor handles cloning
   *
   * @param dist value -> probability map
   * @return sorted SparsePmf
   */
  public static SparsePmf fromMap(Map<Integer, Double> dist) {
    List<Map.Entry<Integer, Double>> sorted =
        dist.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();

    int[] values = sorted.stream().mapToInt(Map.Entry::getKey).toArray();
    double[] probs = sorted.stream().mapToDouble(Map.Entry::getValue).toArray();
    return new SparsePmf(values, probs);
  }

  /** non-zero entry count */
  public int size() {
    return values.length;
  }

  /** Get value by index */
  public int valueAt(int idx) {
    return values[idx];
  }

  /** Get probability by index */
  public double probAt(int idx) {
    return probs[idx];
  }

  /** Maximum value (last element since sorted) */
  public int maxValue() {
    return values.length > 0 ? values[values.length - 1] : 0;
  }

  /**
   * Get total mass
   *
   * @param useKahan whether to use Kahan summation (true for precision)
   * @return total mass
   * @deprecated Use {@link PmfCalculator#totalMass(SparsePmf, boolean)} instead
   */
  @Deprecated
  public double totalMass(boolean useKahan) {
    return useKahan ? totalMassKahan() : totalMassSimple();
  }

  /** Simple cumulative sum */
  private double totalMassSimple() {
    double sum = 0.0;
    for (double p : probs) {
      sum += p;
    }
    return sum;
  }

  /**
   * Precise total mass using Kahan summation
   *
   * @deprecated Use {@link PmfCalculator#totalMassKahan(SparsePmf)} instead
   */
  @Deprecated
  public double totalMassKahan() {
    double sum = 0.0;
    double c = 0.0;
    for (double p : probs) {
      double y = p - c;
      double t = sum + y;
      c = (t - sum) - y;
      sum = t;
    }
    return sum;
  }

  /** Check if any probability value is negative */
  public boolean hasNegative(double tolerance) {
    for (double p : probs) {
      if (p < -tolerance) {
        return true;
      }
    }
    return false;
  }

  /** Check if any probability value is NaN or Infinite */
  public boolean hasNaNOrInf() {
    for (double p : probs) {
      if (Double.isNaN(p) || Double.isInfinite(p)) {
        return true;
      }
    }
    return false;
  }

  /** Check if any probability value exceeds 1.0 */
  public boolean hasValueExceedingOne() {
    for (double p : probs) {
      if (p > 1.0 + 1e-10) {
        return true;
      }
    }
    return false;
  }
}
