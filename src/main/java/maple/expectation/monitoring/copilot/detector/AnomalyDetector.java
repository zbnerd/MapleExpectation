package maple.expectation.monitoring.copilot.detector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.monitoring.copilot.model.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Anomaly Detector for Metric Analysis
 *
 * <h3>Detection Methods</h3>
 * <ul>
 *   <li>Threshold-based: Check against warn/crit thresholds</li>
 *   <li>Z-score: Statistical anomaly detection using mean/stdDev</li>
 *   <li>Hybrid: Both methods combined</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>
 * Optional&lt;AnomalyEvent&gt; anomaly = detector.detect(
 *     signalDefinition,
 *     timeSeriesList,
 *     System.currentTimeMillis(),
 *     zScoreConfig
 * );
 * </pre>
 *
 * @see SignalDefinition
 * @see AnomalyEvent
 * @see ZScoreConfig
 */
@Slf4j
@RequiredArgsConstructor
public class AnomalyDetector {

    /**
     * Detect anomalies in time series data
     *
     * @param signal Signal definition with thresholds
     * @param timeSeriesList Historical time series data
     * @param nowMillis Current timestamp for detection
     * @param zScoreConfig Z-score configuration (can be null/empty for threshold-only)
     * @return Optional AnomalyEvent if anomaly detected
     */
    public Optional<AnomalyEvent> detect(
            SignalDefinition signal,
            List<TimeSeries> timeSeriesList,
            long nowMillis,
            ZScoreConfig zScoreConfig) {

        if (timeSeriesList == null || timeSeriesList.isEmpty()) {
            log.debug("[AnomalyDetector] No time series data for signal: {}", signal.panelTitle());
            return Optional.empty();
        }

        // Extract current value from most recent time series
        Double currentValue = extractLatestValue(timeSeriesList);
        if (currentValue == null) {
            log.debug("[AnomalyDetector] No valid metric value for signal: {}", signal.panelTitle());
            return Optional.empty();
        }

        // 1. Threshold-based detection
        Optional<AnomalyEvent> thresholdAnomaly = detectThresholdBased(signal, currentValue, nowMillis);
        if (thresholdAnomaly.isPresent()) {
            return thresholdAnomaly;
        }

        // 2. Z-score detection (if enabled)
        if (zScoreConfig != null && zScoreConfig.isEnabled()) {
            return detectZScoreBased(signal, timeSeriesList, currentValue, nowMillis, zScoreConfig);
        }

        return Optional.empty();
    }

    /**
     * Threshold-based anomaly detection
     */
    private Optional<AnomalyEvent> detectThresholdBased(
            SignalDefinition signal,
            double currentValue,
            long detectedAtMillis) {

        SeverityMapping severityMapping = signal.severityMapping();
        if (severityMapping == null) {
            return Optional.empty();
        }

        Double warnThreshold = severityMapping.warnThreshold();
        Double critThreshold = severityMapping.critThreshold();
        String comparator = severityMapping.comparator();

        // Check critical threshold first
        if (critThreshold != null && exceedsThreshold(currentValue, critThreshold, comparator)) {
            return Optional.of(AnomalyEvent.builder()
                    .signalId(signal.id().toString())
                    .severity("CRITICAL")
                    .reason(buildReason(signal, currentValue, critThreshold, comparator, "CRITICAL"))
                    .detectedAtMillis(detectedAtMillis)
                    .currentValue(currentValue)
                    .baselineValue(critThreshold)
                    .build());
        }

        // Check warning threshold
        if (warnThreshold != null && exceedsThreshold(currentValue, warnThreshold, comparator)) {
            return Optional.of(AnomalyEvent.builder()
                    .signalId(signal.id().toString())
                    .severity("WARNING")
                    .reason(buildReason(signal, currentValue, warnThreshold, comparator, "WARNING"))
                    .detectedAtMillis(detectedAtMillis)
                    .currentValue(currentValue)
                    .baselineValue(warnThreshold)
                    .build());
        }

        return Optional.empty();
    }

    /**
     * Z-score based anomaly detection
     */
    private Optional<AnomalyEvent> detectZScoreBased(
            SignalDefinition signal,
            List<TimeSeries> timeSeriesList,
            double currentValue,
            long detectedAtMillis,
            ZScoreConfig config) {

        config.validate();

        // Extract all metric values from time series
        List<Double> values = extractAllValues(timeSeriesList);
        if (values.size() < config.getMinRequiredPoints()) {
            log.debug("[AnomalyDetector] Insufficient data for Z-score: {}/{} required",
                    values.size(), config.getMinRequiredPoints());
            return Optional.empty();
        }

        // Calculate statistics
        double mean = calculateMean(values);
        double stdDev = calculateStdDev(values, mean);

        if (stdDev == 0.0) {
            log.debug("[AnomalyDetector] Zero stdDev - all values are identical for signal: {}",
                    signal.panelTitle());
            return Optional.empty();
        }

        // Calculate Z-score
        double zScore = Math.abs((currentValue - mean) / stdDev);

        log.debug("[AnomalyDetector] Z-score calculation for {}: mean={:.2f}, stdDev={:.2f}, z={:.2f}",
                signal.panelTitle(), mean, stdDev, zScore);

        if (zScore >= config.getThreshold()) {
            String severity = determineSeverityFromZScore(zScore);
            return Optional.of(AnomalyEvent.builder()
                    .signalId(signal.id().toString())
                    .severity(severity)
                    .reason(buildZScoreReason(signal, currentValue, mean, stdDev, zScore, config))
                    .detectedAtMillis(detectedAtMillis)
                    .currentValue(currentValue)
                    .baselineValue(mean)
                    .build());
        }

        return Optional.empty();
    }

    /**
     * Extract latest value from time series
     */
    private Double extractLatestValue(List<TimeSeries> timeSeriesList) {
        if (timeSeriesList.isEmpty()) {
            return null;
        }

        TimeSeries latest = timeSeriesList.get(0);
        if (latest.points() == null || latest.points().isEmpty()) {
            return null;
        }

        // Get most recent point
        MetricPoint latestPoint = latest.points().get(latest.points().size() - 1);
        return latestPoint.value();
    }

    /**
     * Extract all metric values from time series
     */
    private List<Double> extractAllValues(List<TimeSeries> timeSeriesList) {
        List<Double> values = new ArrayList<>();

        for (TimeSeries series : timeSeriesList) {
            if (series.points() != null) {
                for (MetricPoint point : series.points()) {
                    if (point.value() != null && !point.value().isNaN() && !point.value().isInfinite()) {
                        values.add(point.value());
                    }
                }
            }
        }

        return values;
    }

    /**
     * Check if value exceeds threshold based on comparator
     */
    private boolean exceedsThreshold(double value, double threshold, String comparator) {
        if (comparator == null) {
            comparator = ">"; // default
        }

        return switch (comparator.trim()) {
            case ">", "gt", "greater than" -> value > threshold;
            case ">=", "gte", "greater than or equal" -> value >= threshold;
            case "<", "lt", "less than" -> value < threshold;
            case "<=", "lte", "less than or equal" -> value <= threshold;
            case "==", "eq", "equal" -> Math.abs(value - threshold) < 0.0001;
            default -> {
                log.warn("[AnomalyDetector] Unknown comparator: {}, defaulting to '>'", comparator);
                yield value > threshold;
            }
        };
    }

    /**
     * Calculate arithmetic mean
     */
    private double calculateMean(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }

        double sum = 0.0;
        for (Double value : values) {
            sum += value;
        }

        return sum / values.size();
    }

    /**
     * Calculate standard deviation (sample stdDev: n-1)
     */
    private double calculateStdDev(List<Double> values, double mean) {
        if (values.size() < 2) {
            return 0.0;
        }

        double sumSquaredDiff = 0.0;
        for (Double value : values) {
            double diff = value - mean;
            sumSquaredDiff += diff * diff;
        }

        return Math.sqrt(sumSquaredDiff / (values.size() - 1));
    }

    /**
     * Determine severity from Z-score magnitude
     */
    private String determineSeverityFromZScore(double zScore) {
        if (zScore >= 4.0) {
            return "CRITICAL";
        } else if (zScore >= 3.0) {
            return "CRITICAL";
        } else if (zScore >= 2.5) {
            return "WARNING";
        }
        return "WARNING";
    }

    /**
     * Build threshold-based reason message
     */
    private String buildReason(
            SignalDefinition signal,
            double currentValue,
            double threshold,
            String comparator,
            String severity) {

        return String.format(
                "[%s] %s: Current value %.2f %s threshold %.2f (%s)",
                severity,
                signal.panelTitle(),
                currentValue,
                comparator != null ? comparator : ">",
                threshold,
                signal.unit() != null ? signal.unit() : ""
        ).trim();
    }

    /**
     * Build Z-score based reason message
     */
    private String buildZScoreReason(
            SignalDefinition signal,
            double currentValue,
            double mean,
            double stdDev,
            double zScore,
            ZScoreConfig config) {

        return String.format(
                "[Z-SCORE] %s: Value %.2f deviates %.2fσ from baseline %.2f (σ=%.2f, threshold=%.1f)",
                signal.panelTitle(),
                currentValue,
                zScore,
                mean,
                stdDev,
                config.getThreshold()
        );
    }
}
