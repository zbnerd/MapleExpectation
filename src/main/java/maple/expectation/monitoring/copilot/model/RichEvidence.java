package maple.expectation.monitoring.copilot.model;

import lombok.Builder;

import java.time.Instant;

/**
 * Rich evidence with PromQL evaluation results.
 *
 * @param signalId Signal identifier
 * @param signalName Signal display name
 * @param promql PromQL query used
 * @param currentValue Current metric value
 * @param baselineValue Baseline metric value (5 min ago)
 * @param deviationPercent Deviation from baseline (%)
 * @param evaluatedAt Query evaluation timestamp
 */
@Builder
public record RichEvidence(
    String signalId,
    String signalName,
    String promql,
    double currentValue,
    double baselineValue,
    double deviationPercent,
    Instant evaluatedAt
) {
    /**
     * Format deviation as human-readable string.
     * Example: "+25.3%" (increase) or "-12.8%" (decrease)
     */
    public String formattedDeviation() {
        String sign = deviationPercent >= 0 ? "+" : "";
        return String.format("%s%.2f%%", sign, deviationPercent);
    }

    /**
     * Get severity direction based on deviation.
     * @return "INCREASE" if positive, "DECREASE" if negative
     */
    public String deviationDirection() {
        return deviationPercent >= 0 ? "INCREASE" : "DECREASE";
    }
}
