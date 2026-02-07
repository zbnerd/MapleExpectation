package maple.expectation.monitoring.copilot.pipeline;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.monitoring.ai.AiSreService;
import maple.expectation.monitoring.copilot.client.PrometheusClient;
import maple.expectation.monitoring.copilot.detector.AnomalyDetector;
import maple.expectation.monitoring.copilot.ingestor.GrafanaJsonIngestor;
import maple.expectation.monitoring.copilot.model.*;
import maple.expectation.monitoring.copilot.notifier.DiscordNotifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Monitoring Pipeline Service (Issue #251)
 *
 * <h3>Complete Pipeline Flow</h3>
 *
 * <pre>
 * 1. Grafana JSON Ingestion → SignalDefinition Catalog
 * 2. Prometheus Query → Metric Values (query_range)
 * 3. Anomaly Detection → AnomalyEvent List
 * 4. AI SRE Analysis → MitigationPlan
 * 5. Discord Notification → Formatted Alert
 * </pre>
 *
 * <h3>De-duplication</h3>
 *
 * <p>Prevents alert spam by tracking recent incidents via incidentId.
 *
 * <h4>CLAUDE.md Compliance</h4>
 *
 * <ul>
 *   <li>Section 12: All exceptions via LogicExecutor
 *   <li>Section 12-1: Circuit breaker for AI calls
 * </ul>
 *
 * @see GrafanaJsonIngestor
 * @see PrometheusClient
 * @see AnomalyDetector
 * @see AiSreService
 * @see DiscordNotifier
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.monitoring.enabled", havingValue = "true", matchIfMissing = true)
public class MonitoringPipelineService {

  private final GrafanaJsonIngestor ingestor;
  private final PrometheusClient prometheusClient;
  private final AnomalyDetector detector;
  private final Optional<AiSreService> aiSreService;
  private final DiscordNotifier discordNotifier;
  private final LogicExecutor executor;

  public MonitoringPipelineService(
      GrafanaJsonIngestor ingestor,
      PrometheusClient prometheusClient,
      AnomalyDetector detector,
      Optional<AiSreService> aiSreService,
      DiscordNotifier discordNotifier,
      LogicExecutor executor) {
    this.ingestor = ingestor;
    this.prometheusClient = prometheusClient;
    this.detector = detector;
    this.aiSreService = aiSreService;
    this.discordNotifier = discordNotifier;
    this.executor = executor;
  }

  @Value("${app.monitoring.grafana.dashboards-path:./grafana/dashboards}")
  private String dashboardsPath;

  @Value("${app.monitoring.prometheus.base-url:http://localhost:9090}")
  private String prometheusBaseUrl;

  @Value("${app.monitoring.interval-seconds:15}")
  private int intervalSeconds;

  @Value("${app.monitoring.query-range-seconds:300}")
  private int queryRangeSeconds;

  // De-duplication: Recent incident IDs (last 1 hour)
  private final Map<String, Long> recentIncidents = new ConcurrentHashMap<>();

  /**
   * Scheduled monitoring task (every 15 seconds default)
   *
   * <p>Fixed-rate scheduling ensures continuous monitoring even if previous execution takes longer
   * than interval.
   */
  @Scheduled(fixedRateString = "${app.monitoring.interval-seconds:15000}")
  public void runMonitoringCycle() {
    executor.executeVoid(this::executePipeline, TaskContext.of("MonitoringPipeline", "RunCycle"));
  }

  /** Complete monitoring pipeline execution */
  private void executePipeline() {
    long now = System.currentTimeMillis();

    // 1. Clean old incidents (older than 1 hour)
    cleanOldIncidents(now);

    // 2. Ingest Grafana dashboards (reload catalog)
    List<SignalDefinition> signals = loadSignalDefinitions();

    // 3. Query Prometheus for all signals
    Map<String, List<TimeSeries>> metrics = queryMetrics(signals, now);

    // 4. Detect anomalies
    List<AnomalyEvent> anomalies = detectAnomalies(signals, metrics, now);

    if (anomalies.isEmpty()) {
      log.debug("[MonitoringPipeline] No anomalies detected");
      return;
    }

    log.info("[MonitoringPipeline] {} anomalies detected", anomalies.size());

    // 5. Build incident context
    IncidentContext context = buildIncidentContext(anomalies, signals, metrics);

    // 6. De-duplication check
    if (isRecentIncident(context.incidentId())) {
      log.info("[MonitoringPipeline] Incident {} already recent, skipping", context.incidentId());
      return;
    }

    // 6.1 Track incident IMMEDIATELY to prevent race conditions
    trackIncident(context.incidentId(), now);

    // 7. AI SRE analysis
    AiSreService.MitigationPlan plan =
        aiSreService
            .map(service -> service.analyzeIncident(context))
            .orElseGet(() -> createDefaultMitigationPlan(context));

    // 8. Send Discord alert
    sendDiscordAlert(context, plan);
  }

  /** Step 1: Load signal definitions from Grafana dashboards */
  private List<SignalDefinition> loadSignalDefinitions() {
    return executor.executeOrDefault(
        () -> ingestor.ingestDashboards(java.nio.file.Path.of(dashboardsPath)),
        List.of(),
        TaskContext.of("MonitoringPipeline", "LoadSignals"));
  }

  /** Step 2: Query Prometheus for all signals */
  private Map<String, List<TimeSeries>> queryMetrics(List<SignalDefinition> signals, long now) {
    Map<String, List<TimeSeries>> metrics = new HashMap<>();

    Instant end = Instant.ofEpochMilli(now);
    Instant start = end.minus(queryRangeSeconds, ChronoUnit.SECONDS);
    String step = "15s"; // 15-second resolution

    for (SignalDefinition signal : signals) {
      List<PrometheusClient.TimeSeries> prometheusSeries =
          prometheusClient.queryRange(signal.query(), start, end, step);

      if (!prometheusSeries.isEmpty()) {
        // Convert Prometheus TimeSeries to model TimeSeries
        List<TimeSeries> modelSeries =
            prometheusSeries.stream().map(this::convertToModelTimeSeries).toList();
        metrics.put(signal.id(), modelSeries);
      }
    }

    return metrics;
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

  /** Step 3: Detect anomalies across all signals */
  private List<AnomalyEvent> detectAnomalies(
      List<SignalDefinition> signals, Map<String, List<TimeSeries>> metrics, long now) {
    List<AnomalyEvent> anomalies = new ArrayList<>();

    for (SignalDefinition signal : signals) {
      List<TimeSeries> series = metrics.get(signal.id());
      if (series == null || series.isEmpty()) {
        continue;
      }

      Optional<AnomalyEvent> anomaly =
          detector.detect(
              signal,
              series,
              now,
              ZScoreConfig.builder()
                  .enabled(true)
                  .windowPoints(60)
                  .threshold(3.0)
                  .minRequiredPoints(10)
                  .build());

      anomaly.ifPresent(anomalies::add);
    }

    return anomalies;
  }

  /** Step 4: Build incident context for AI analysis */
  private IncidentContext buildIncidentContext(
      List<AnomalyEvent> anomalies,
      List<SignalDefinition> signals,
      Map<String, List<TimeSeries>> metrics) {
    // Generate incident ID
    String incidentId = generateIncidentId(anomalies);

    // Build evidence from top anomalies
    List<EvidenceItem> evidence = buildEvidence(anomalies, signals, metrics);

    // Build metadata
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("anomalyCount", anomalies.size());
    metadata.put("timestamp", Instant.now().toString());
    metadata.put("prometheusUrl", prometheusBaseUrl);

    return new IncidentContext(incidentId, buildSummary(anomalies), anomalies, evidence, metadata);
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

  /** Step 7: Send Discord alert */
  private void sendDiscordAlert(IncidentContext context, AiSreService.MitigationPlan plan) {
    // Prepare data for Discord formatting
    Map<String, SignalDefinition> signalMap = findSignalsForAnomalies(context.anomalies());

    List<DiscordNotifier.AnnotatedSignal> annotatedSignals =
        context.anomalies().stream()
            .limit(3)
            .map(
                anomaly ->
                    new DiscordNotifier.AnnotatedSignal(
                        signalMap.get(anomaly.signalId()), anomaly.currentValue()))
            .toList();

    List<String> hypotheses =
        plan.hypotheses().stream()
            .limit(2)
            .map(h -> String.format("**%s** (confidence: %s)", h.cause(), h.confidence()))
            .toList();

    List<String> actions =
        plan.actions().stream()
            .limit(2)
            .map(a -> String.format("%d. %s [risk: %s]", a.step(), a.action(), a.risk()))
            .toList();

    // Determine severity from context
    String severity =
        context.anomalies().stream().anyMatch(a -> "CRIT".equals(a.severity())) ? "CRIT" : "WARN";

    // Format and send
    String message =
        discordNotifier.formatIncidentMessage(
            context.incidentId(), severity, annotatedSignals, hypotheses, actions);

    discordNotifier.send(message);
  }

  /** Find signal definitions for anomalies */
  private Map<String, SignalDefinition> findSignalsForAnomalies(List<AnomalyEvent> anomalies) {
    // Reload signals to get full definitions
    List<SignalDefinition> signals = loadSignalDefinitions();

    return signals.stream().collect(HashMap::new, (map, s) -> map.put(s.id(), s), HashMap::putAll);
  }

  /**
   * De-duplication: Check if incident is recent 5-minute window to prevent duplicate alerts (AI SRE
   * recommendation)
   */
  private boolean isRecentIncident(String incidentId) {
    Long timestamp = recentIncidents.get(incidentId);
    if (timestamp == null) {
      return false;
    }

    // Consider recent if within 5 minutes
    long age = System.currentTimeMillis() - timestamp;
    return age < 300_000; // 5 minutes in milliseconds
  }

  /** Track incident to prevent duplicates */
  private void trackIncident(String incidentId, long timestamp) {
    recentIncidents.put(incidentId, timestamp);
    log.debug("[MonitoringPipeline] Tracked incident: {}", incidentId);
  }

  /** Clean old incidents (older than 5 minutes) 5-minute dedup window (AI SRE recommendation) */
  private void cleanOldIncidents(long now) {
    long threshold = now - 300_000; // 5 minutes ago

    recentIncidents
        .entrySet()
        .removeIf(
            entry -> {
              boolean isOld = entry.getValue() < threshold;
              if (isOld) {
                log.debug("[MonitoringPipeline] Cleaned old incident: {}", entry.getKey());
              }
              return isOld;
            });
  }

  /** Create default mitigation plan when AI SRE is not available */
  private AiSreService.MitigationPlan createDefaultMitigationPlan(IncidentContext context) {
    log.warn("[MonitoringPipeline] AI SRE not available, using default mitigation plan");

    List<AiSreService.Hypothesis> defaultHypotheses =
        List.of(
            new AiSreService.Hypothesis(
                "AI SRE service not available - manual analysis required",
                "LOW",
                List.of("AI analysis disabled", "Manual investigation needed")));

    List<AiSreService.Action> defaultActions =
        List.of(
            new AiSreService.Action(1, "Review system logs", "LOW", "Identify root cause"),
            new AiSreService.Action(2, "Check metrics dashboard", "LOW", "Verify anomaly details"),
            new AiSreService.Action(
                3, "Escalate to on-call engineer", "LOW", "Human intervention required"));

    AiSreService.RollbackPlan rollbackPlan =
        new AiSreService.RollbackPlan(
            "If symptoms worsen", List.of("Revert recent changes", "Scale up resources"));

    return new AiSreService.MitigationPlan(
        context.incidentId(),
        "RULE_BASED_FALLBACK",
        defaultHypotheses,
        defaultActions,
        List.of(),
        rollbackPlan,
        "AI SRE service not available. Please enable ai.sre.enabled=true for AI-powered analysis.");
  }
}
