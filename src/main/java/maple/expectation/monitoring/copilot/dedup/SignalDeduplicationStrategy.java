package maple.expectation.monitoring.copilot.dedup;

import maple.expectation.monitoring.copilot.model.AnomalyEvent;
import maple.expectation.monitoring.copilot.model.SignalDefinition;

/**
 * Strategy interface for signal deduplication.
 *
 * <p>Implementations provide different approaches to prevent duplicate
 * anomaly notifications within a time window.</p>
 *
 * <h3>CLAUDE.md Compliance</h3>
 * <ul>
 *   <li>Section 4: Strategy Pattern for pluggable deduplication algorithms</li>
 *   <li>Section 6: Interface Segregation Principle (ISP)</li>
 *   <li>Stateless: No server-bound state for scale-out</li>
 * </ul>
 *
 * @see TimeBasedSlidingWindowStrategy
 */
public interface SignalDeduplicationStrategy {

    /**
     * Check if an anomaly event should be skipped due to recent detection.
     *
     * @param event The anomaly event to check
     * @param signal The signal definition (for query/threshold info)
     * @param currentTimestamp Current timestamp in milliseconds
     * @return true if the event should be skipped (duplicate), false if it should be processed
     */
    boolean shouldSkip(AnomalyEvent event, SignalDefinition signal, long currentTimestamp);

    /**
     * Record an anomaly detection for future deduplication.
     *
     * @param event The anomaly event that was detected
     * @param currentTimestamp Current timestamp in milliseconds
     */
    void recordDetection(AnomalyEvent event, long currentTimestamp);

    /**
     * Cleanup stale entries from deduplication state.
     *
     * @param currentTimestamp Current timestamp in milliseconds
     */
    void cleanup(long currentTimestamp);
}
