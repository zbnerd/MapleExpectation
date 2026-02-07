package maple.expectation.monitoring.copilot.client;

import java.time.Instant;

/**
 * Result of evaluating a PromQL instant query.
 *
 * @param promql The query that was evaluated
 * @param value The scalar result value
 * @param timestamp The query evaluation timestamp
 */
public record QueryEvaluation(String promql, double value, Instant timestamp) {
  /**
   * Calculate deviation from baseline as percentage.
   *
   * @param baseline Baseline value to compare against
   * @return Deviation percentage (positive = increase, negative = decrease)
   */
  public double deviationFrom(double baseline) {
    if (baseline == 0.0) {
      return value > 0 ? 100.0 : 0.0;
    }
    return ((value - baseline) / baseline) * 100.0;
  }
}
