package maple.expectation.monitoring.copilot.pipeline;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.monitoring.copilot.client.PrometheusClient;
import maple.expectation.monitoring.copilot.detector.AnomalyDetector;
import maple.expectation.monitoring.copilot.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Anomaly Detection Orchestrator (Issue #251)
 *
 * <h3>Responsibility</h3>
 *
 * <p>Coordinates the complete anomaly detection pipeline:
 *
 * <pre>
 * 1. Prometheus Query → Metric Values (query_range)
 * 2. TimeSeries Conversion → Internal Model
 * 3. Anomaly Detection → AnomalyEvent List
 * 4. Incident Context → Aggregated Detection Results
 * </pre>
 *
 * <h3>Prometheus Query Strategy</h3>
 *
 * <ul>
 *   <li>Query type: {@code query_range} for time-series data
 *   <li>Lookback window: Configurable (default 5 minutes)
 *   <li>Resolution: Configurable step (default 15 seconds)
 *   <li>Rate limiting: Sequential queries with error handling
 * </ul>
 *
 * <h3>Detection Algorithms</h3>
 *
 * <p>Delegates to {@link AnomalyDetector} with pluggable algorithms (Z-Score, IQR, etc.).
 *
 * <h3>LogicExecutor Compliance</h3>
 *
 * <p>All operations wrapped in {@link LogicExecutor} for resilience and observability (Section
 * 12).
 *
 * @see PrometheusClient
 * @see AnomalyDetector
 * @see SignalDefinition
 * @see AnomalyEvent
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monitoring.copilot.enabled", havingValue = "true")
public class AnomalyDetectionOrchestrator {

  private final PrometheusClient prometheusClient;
  private final AnomalyDetector detector;
  private final LogicExecutor executor;

  @Value("${monitoring.copilot.prometheus.step:15s}")
  private String queryStep;

  @Value("${monitoring.copilot.query-range-seconds:300}")
  private int queryRangeSeconds;

  /**
   * Detect anomalies across multiple signals.
   *
   * <p>Executes the complete detection pipeline for all provided signals:
   *
   * <ol>
   *   <li>Query Prometheus for each signal
   *   <li>Convert response to internal TimeSeries model
   *   <li>Run anomaly detection algorithm
   *   <li>Aggregate results into list of AnomalyEvents
   * </ol>
   *
   * @param signals List of signal definitions to analyze
   * @param currentTimestamp Current timestamp for detection window
   * @return List of detected anomalies, empty if none found
   */
  public List<AnomalyEvent> detectAnomalies(List<SignalDefinition> signals, long currentTimestamp) {
    List<AnomalyEvent> allAnomalies = new ArrayList<>();

    // 1. Query Prometheus for all signals
    Map<String, List<TimeSeries>> metrics = queryMetrics(signals, currentTimestamp);

    // 2. Detect anomalies for each signal
    for (SignalDefinition signal : signals) {
      List<TimeSeries> series = metrics.get(signal.id());

      if (series == null || series.isEmpty()) {
        log.debug("[AnomalyDetectionOrchestrator] No metrics for signal: {}", signal.id());
        continue;
      }

      // 3. Run detection algorithm
      Optional<AnomalyEvent> anomaly =
          detector.detect(
              signal,
              series,
              currentTimestamp,
              ZScoreConfig.builder()
                  .enabled(true)
                  .windowPoints(60)
                  .threshold(3.0)
                  .minRequiredPoints(10)
                  .build());

      // 4. Collect result
      anomaly.ifPresent(
          event -> {
            allAnomalies.add(event);
            log.debug(
                "[AnomalyDetectionOrchestrator] Anomaly detected: {} = {}",
                signal.panelTitle(),
                event.currentValue());
          });
    }

    log.info(
        "[AnomalyDetectionOrchestrator] Detection complete: {}/{} signals with anomalies",
        allAnomalies.size(),
        signals.size());

    return allAnomalies;
  }

  /**
   * Query Prometheus for all signals and convert to internal model.
   *
   * @param signals List of signal definitions to query
   * @param currentTimestamp Current timestamp for query window
   * @return Map of signal ID to TimeSeries list
   */
  private Map<String, List<TimeSeries>> queryMetrics(
      List<SignalDefinition> signals, long currentTimestamp) {
    Map<String, List<TimeSeries>> metrics = new HashMap<>();

    Instant end = Instant.ofEpochMilli(currentTimestamp);
    Instant start = end.minus(queryRangeSeconds, ChronoUnit.SECONDS);

    for (SignalDefinition signal : signals) {
      List<PrometheusClient.TimeSeries> prometheusSeries =
          executor.executeOrDefault(
              () -> prometheusClient.queryRange(signal.query(), start, end, queryStep),
              List.of(),
              TaskContext.of("AnomalyDetectionOrchestrator", "QueryPrometheus", signal.id()));

      if (!prometheusSeries.isEmpty()) {
        // Convert Prometheus TimeSeries to model TimeSeries
        List<TimeSeries> modelSeries =
            prometheusSeries.stream().map(this::convertToModelTimeSeries).toList();
        metrics.put(signal.id(), modelSeries);

        log.debug(
            "[AnomalyDetectionOrchestrator] Queried {}: {} series",
            signal.panelTitle(),
            modelSeries.size());
      }
    }

    return metrics;
  }

  /**
   * Build incident context from detected anomalies.
   *
   * <p>Aggregates anomaly events into a unified incident context for AI analysis and alerting.
   *
   * @param anomalies List of detected anomalies
   * @param signals Original signal definitions for metadata
   * @param metrics Queried metrics for evidence
   * @return Populated incident context
   */
  public IncidentContext buildIncidentContext(
      List<AnomalyEvent> anomalies,
      List<SignalDefinition> signals,
      Map<String, List<TimeSeries>> metrics) {
    String incidentId = generateIncidentId(anomalies);
    String summary = buildSummary(anomalies);
    List<EvidenceItem> evidence = buildEvidence(anomalies, signals, metrics);

    Map<String, Object> metadata = new HashMap<>();
    metadata.put("anomalyCount", anomalies.size());
    metadata.put("timestamp", Instant.now().toString());
    metadata.put("detectionMethod", "ZScore");

    return new IncidentContext(incidentId, summary, anomalies, evidence, metadata);
  }

  /** Convert Prometheus TimeSeries to model TimeSeries */
  private TimeSeries convertToModelTimeSeries(PrometheusClient.TimeSeries promSeries) {
    String label = promSeries.metric().toString();

    List<MetricPoint> points =
        promSeries.values().stream()
            .map(
                vp ->
                    new MetricPoint(
                        vp.timestamp() * 1000, // Convert seconds to milliseconds
                        vp.getValueAsDouble()))
            .toList();

    return new TimeSeries(label, points);
  }

  /** Generate unique incident ID from anomaly signatures */
  private String generateIncidentId(List<AnomalyEvent> anomalies) {
    // Create signature from top 3 anomaly signal IDs
    String signature =
        anomalies.stream()
            .limit(3)
            .map(AnomalyEvent::signalId)
            .sorted()
            .reduce((a, b) -> a + "," + b)
            .orElse("unknown");

    // Hash to create stable ID
    int hash = signature.hashCode();
    long epochMinute = Instant.now().getEpochSecond() / 60; // Minute-level granularity

    return String.format("INC-%d-%08x", epochMinute, Math.abs(hash));
  }

  /** Build incident summary */
  private String buildSummary(List<AnomalyEvent> anomalies) {
    long critCount = anomalies.stream().filter(a -> "CRIT".equals(a.severity())).count();
    long warnCount = anomalies.size() - critCount;

    return String.format("%d CRIT, %d WARN anomalies detected", critCount, warnCount);
  }

  /** Build evidence items for top anomalies */
  private List<EvidenceItem> buildEvidence(
      List<AnomalyEvent> anomalies,
      List<SignalDefinition> signals,
      Map<String, List<TimeSeries>> metrics) {
    Map<String, SignalDefinition> signalMap =
        signals.stream().collect(HashMap::new, (map, s) -> map.put(s.id(), s), HashMap::putAll);

    List<EvidenceItem> evidence = new ArrayList<>();

    for (AnomalyEvent anomaly : anomalies) {
      SignalDefinition signal = signalMap.get(anomaly.signalId());
      if (signal == null) {
        continue;
      }

      String body =
          String.format(
              """
              PromQL: %s
              Current: %.4f %s
              Baseline: %.4f %s
              Reason: %s
              """,
              signal.query(),
              anomaly.currentValue(),
              signal.unit() != null ? signal.unit() : "",
              anomaly.baselineValue() != null ? anomaly.baselineValue() : 0.0,
              signal.unit() != null ? signal.unit() : "",
              anomaly.reason());

      evidence.add(new EvidenceItem("PROMQL", signal.panelTitle(), body));
    }

    return evidence;
  }
}
