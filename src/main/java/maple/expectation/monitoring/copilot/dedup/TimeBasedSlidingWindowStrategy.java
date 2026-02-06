package maple.expectation.monitoring.copilot.dedup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.monitoring.copilot.client.PrometheusClient;
import maple.expectation.monitoring.copilot.model.AnomalyEvent;
import maple.expectation.monitoring.copilot.model.SignalDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Stateless time-based sliding window deduplication strategy.
 *
 * <p>Prevents duplicate notifications by querying Prometheus for recent anomalies
 * within the deduplication window. NO in-memory state - completely stateless and
 * scale-out friendly.</p>
 *
 * <h3>Stateless Design</h3>
 * <p>Uses PromQL re-query to check if threshold was exceeded in the sliding window.
 * Prometheus is the single source of truth. Server restarts or multiple instances
 * do not affect deduplication accuracy.</p>
 *
 * <h3>CLAUDE.md Compliance</h3>
 * <ul>
 *   <li>Section 4: Strategy Pattern implementation</li>
 *   <li>Section 12: LogicExecutor pattern for exception handling</li>
 *   <li>Stateless: No server-bound state (scale-out friendly)</li>
 * </ul>
 *
 * @see SignalDeduplicationStrategy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeBasedSlidingWindowStrategy implements SignalDeduplicationStrategy {

    private final PrometheusClient prometheusClient;
    private final LogicExecutor executor;

    @Value("${monitoring.copilot.dedup-window-minutes:10}")
    private long dedupWindowMinutes;

    @Override
    public boolean shouldSkip(AnomalyEvent event, SignalDefinition signal, long currentTimestamp) {
        return executor.executeOrDefault(
            () -> checkDuplicateInWindow(event, signal, currentTimestamp),
            false, // Fail open: allow if query fails
            TaskContext.of("SignalDedup", "CheckDuplicate", event.signalId())
        );
    }

    @Override
    public void recordDetection(AnomalyEvent event, long currentTimestamp) {
        // NO-OP: Stateless design - no recording needed
        log.debug("[SignalDedup] Stateless mode - recording skipped for: {}", event.signalId());
    }

    @Override
    public void cleanup(long currentTimestamp) {
        // NO-OP: Stateless design - no cleanup needed
        log.debug("[SignalDedup] Stateless mode - cleanup skipped");
    }

    /**
     * Check if anomaly was already detected in the sliding window using PromQL re-query.
     *
     * <p>This is the CORE of stateless deduplication:</p>
     * <ol>
     *   <li>Query Prometheus for the time window</li>
     *   <li>Check if threshold was exceeded recently</li>
     *   <li>If yes, skip as duplicate</li>
     * </ol>
     *
     * @param event The anomaly event to check
     * @param signal The signal definition with PromQL and threshold
     * @param currentTimestamp Current timestamp in milliseconds
     * @return true if duplicate detected, false otherwise
     */
    private boolean checkDuplicateInWindow(AnomalyEvent event, SignalDefinition signal, long currentTimestamp) {
        Instant detectedAt = Instant.ofEpochMilli(currentTimestamp);
        Instant windowStart = Instant.ofEpochMilli(currentTimestamp - (dedupWindowMinutes * 60 * 1000));

        // Query Prometheus for historical data in the window
        List<PrometheusClient.TimeSeries> timeSeries = prometheusClient.queryRange(
            signal.query(),
            windowStart,
            detectedAt,
            "1m" // 1-minute resolution
        );

        if (timeSeries.isEmpty()) {
            log.debug("[SignalDedup] No historical data for signal: {}", event.signalId());
            return false;
        }

        // Get threshold from signal definition
        double threshold = signal.severityMapping() != null
            ? signal.severityMapping().warnThreshold()
            : 0.0;
        String comparator = signal.severityMapping() != null
            ? signal.severityMapping().comparator()
            : ">";

        // Check if any point in the window exceeded threshold
        for (PrometheusClient.TimeSeries series : timeSeries) {
            for (PrometheusClient.ValuePoint point : series.values()) {
                double value = point.getValueAsDouble();
                boolean exceeded = exceedsThreshold(value, threshold, comparator);

                if (exceeded) {
                    log.debug("[SignalDedup] Duplicate detected: {} at {} (value: {}, threshold: {})",
                        event.signalId(),
                        Instant.ofEpochSecond(point.timestamp()),
                        value,
                        threshold
                    );
                    return true; // Duplicate found
                }
            }
        }

        return false; // No duplicate
    }

    /**
     * Check if value exceeds threshold based on comparator.
     */
    private boolean exceedsThreshold(double value, double threshold, String comparator) {
        if (comparator == null) {
            comparator = ">";
        }

        return switch (comparator.trim()) {
            case ">", "gt", "greater than" -> value > threshold;
            case ">=", "gte", "greater than or equal" -> value >= threshold;
            case "<", "lt", "less than" -> value < threshold;
            case "<=", "lte", "less than or equal" -> value <= threshold;
            default -> value > threshold;
        };
    }
}
