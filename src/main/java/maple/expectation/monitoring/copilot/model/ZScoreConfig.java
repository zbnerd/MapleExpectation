package maple.expectation.monitoring.copilot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Z-Score Configuration for Statistical Anomaly Detection
 *
 * <p>Configures statistical anomaly detection using Z-score (standard deviations from mean).</p>
 *
 * <h3>Z-Score Formula</h3>
 * <pre>
 * z = (value - mean) / stdDev
 * </pre>
 *
 * <h3>Interpretation</h3>
 * <ul>
 *   <li>|z| >= 3.0: Highly anomalous (99.7% confidence)</li>
 *   <li>|z| >= 2.5: Very anomalous (98.8% confidence)</li>
 *   <li>|z| >= 2.0: Moderately anomalous (95.4% confidence)</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZScoreConfig {

    /**
     * Enable Z-score detection
     */
    @Builder.Default
    private boolean enabled = false;

    /**
     * Number of data points to use for calculating mean/stdDev
     * - Recommended: 20-100 points for stable statistics
     * - Too small: High false positive rate
     * - Too large: Slow detection of anomalies
     */
    @Builder.Default
    private int windowPoints = 30;

    /**
     * Z-score threshold for triggering anomaly
     * - 3.0 = 99.7% confidence (3-sigma rule)
     * - 2.5 = 98.8% confidence
     * - 2.0 = 95.4% confidence
     */
    @Builder.Default
    private double threshold = 3.0;

    /**
     * Minimum required points for Z-score calculation
     * - Prevents unreliable statistics with insufficient data
     */
    @Builder.Default
    private int minRequiredPoints = 10;

    /**
     * Validate configuration
     */
    public void validate() {
        if (enabled) {
            if (windowPoints < minRequiredPoints) {
                throw new IllegalArgumentException(
                        String.format("windowPoints (%d) must be >= minRequiredPoints (%d)",
                                windowPoints, minRequiredPoints)
                );
            }
            if (threshold <= 0) {
                throw new IllegalArgumentException(
                        String.format("threshold (%.2f) must be > 0", threshold)
                );
            }
        }
    }
}
