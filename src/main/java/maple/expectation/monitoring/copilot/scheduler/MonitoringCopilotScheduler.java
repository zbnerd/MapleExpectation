package maple.expectation.monitoring.copilot.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.monitoring.ai.AiSreService;
import maple.expectation.monitoring.copilot.client.PrometheusClient;
import maple.expectation.monitoring.copilot.detector.AnomalyDetector;
import maple.expectation.monitoring.copilot.ingestor.GrafanaJsonIngestor;
import maple.expectation.monitoring.copilot.model.AnomalyEvent;
import maple.expectation.monitoring.copilot.model.IncidentContext;
import maple.expectation.monitoring.copilot.model.SignalDefinition;
import maple.expectation.monitoring.copilot.model.TimeSeries;
import maple.expectation.monitoring.copilot.notifier.DiscordNotifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitoring Copilot Scheduler
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Load signal catalog from Grafana JSON (cached 5min)</li>
 *   <li>Query Prometheus for top 10 priority signals</li>
 *   <li>Run detector on each signal</li>
 *   <li>Compose IncidentContext and call AI analysis</li>
 *   <li>Send Discord notification</li>
 *   <li>Deduplication: skip same incident within 10min window</li>
 * </ul>
 *
 * <h3>Execution Interval</h3>
 * <p>Runs every 15 seconds via {@code @Scheduled(fixedRate = 15000)}</p>
 *
 * <h3>LogicExecutor Pattern</h3>
 * <p>All operations wrapped in executor.executeOrDefault() for resilience and observability</p>
 *
 * @see GrafanaJsonIngestor
 * @see PrometheusClient
 * @see AnomalyDetector
 * @see DiscordNotifier
 * @see AiSreService
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monitoring.copilot.enabled", havingValue = "true")
public class MonitoringCopilotScheduler {

    private final GrafanaJsonIngestor grafanaIngestor;
    private final PrometheusClient prometheusClient;
    private final AnomalyDetector detector;
    private final DiscordNotifier discordNotifier;
    private final AiSreService aiSreService;
    private final LogicExecutor executor;

    @Value("${monitoring.copilot.grafana.dashboard-dir:./dashboards}")
    private String dashboardDir;

    @Value("${monitoring.copilot.prometheus.step:1m}")
    private String prometheusQueryStep;

    @Value("${monitoring.copilot.dedup-window-minutes:10}")
    private long dedupWindowMinutes;

    @Value("${monitoring.copilot.top-signals:10}")
    private int topSignalsCount;

    // Deduplication cache: signalId -> last detected timestamp
    private final Map<String, Long> recentDetections = new ConcurrentHashMap<>();

    // Signal catalog cache: updated every 5 minutes
    private volatile List<SignalDefinition> signalCatalogCache = List.of();
    private volatile long catalogLastUpdated = 0L;
    private static final long CATALOG_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    /**
     * Main scheduled task: runs every 15 seconds
     */
    @Scheduled(fixedRate = 15000)
    public void monitorAndDetect() {
        TaskContext context = TaskContext.of("MonitoringCopilot", "ScheduledDetection");

        executor.executeVoid(() -> {
            long now = System.currentTimeMillis();

            // 1. Load signal catalog (with 5min cache)
            List<SignalDefinition> signals = loadSignalCatalog(now);

            if (signals.isEmpty()) {
                log.debug("[MonitoringCopilot] No signals loaded, skipping detection cycle");
                return;
            }

            // 2. Select top N priority signals
            List<SignalDefinition> topSignals = selectTopPrioritySignals(signals);
            log.debug("[MonitoringCopilot] Selected {} top priority signals", topSignals.size());

            // 3. Query Prometheus and run detection
            List<AnomalyEvent> detectedAnomalies = detectAnomalies(topSignals, now);

            if (detectedAnomalies.isEmpty()) {
                log.debug("[MonitoringCopilot] No anomalies detected in this cycle");
                return;
            }

            log.info("[MonitoringCopilot] Detected {} anomalies", detectedAnomalies.size());

            // 4. Compose incident context and analyze
            processIncident(detectedAnomalies, now);

            // 5. Cleanup stale dedup entries
            cleanupDedupCache(now);

        }, context);
    }

    /**
     * Load signal catalog from Grafana JSON (cached 5min)
     */
    private List<SignalDefinition> loadSignalCatalog(long now) {
        // Cache hit
        if (now - catalogLastUpdated < CATALOG_CACHE_TTL_MS && !signalCatalogCache.isEmpty()) {
            return signalCatalogCache;
        }

        return executor.executeOrDefault(
                () -> {
                    List<SignalDefinition> signals = grafanaIngestor.ingestDashboards(
                            java.nio.file.Path.of(dashboardDir)
                    );

                    // Update cache
                    signalCatalogCache = signals;
                    catalogLastUpdated = now;

                    log.info("[MonitoringCopilot] Signal catalog refreshed: {} signals", signals.size());
                    return signals;
                },
                List.of(),
                TaskContext.of("MonitoringCopilot", "LoadSignalCatalog")
        );
    }

    /**
     * Select top N priority signals based on metadata priority score
     */
    private List<SignalDefinition> selectTopPrioritySignals(List<SignalDefinition> signals) {
        return signals.stream()
                .filter(s -> s.metadata() != null && s.metadata().containsKey("priorityScore"))
                .sorted((s1, s2) -> {
                    int score1 = Integer.parseInt(s1.metadata().get("priorityScore"));
                    int score2 = Integer.parseInt(s2.metadata().get("priorityScore"));
                    return Integer.compare(score2, score1); // Descending order
                })
                .limit(topSignalsCount)
                .toList();
    }

    /**
     * Query Prometheus and detect anomalies for each signal
     */
    private List<AnomalyEvent> detectAnomalies(List<SignalDefinition> signals, long now) {
        List<AnomalyEvent> allAnomalies = new ArrayList<>();

        Instant endTime = Instant.now();
        Instant startTime = endTime.minusSeconds(300); // 5 minutes lookback

        for (SignalDefinition signal : signals) {
            List<AnomalyEvent> signalAnomalies = executor.executeOrDefault(
                    () -> detectSignalAnomalies(signal, startTime, endTime, now),
                    List.of(),
                    TaskContext.of("MonitoringCopilot", "DetectSignal", signal.panelTitle())
            );

            allAnomalies.addAll(signalAnomalies);
        }

        return allAnomalies;
    }

    /**
     * Detect anomalies for a single signal
     */
    private List<AnomalyEvent> detectSignalAnomalies(
            SignalDefinition signal,
            Instant startTime,
            Instant endTime,
            long now) {

        // Deduplication check
        String dedupKey = signal.id().toString();
        Long lastDetected = recentDetections.get(dedupKey);
        if (lastDetected != null && (now - lastDetected) < dedupWindowMinutes * 60 * 1000) {
            log.debug("[MonitoringCopilot] Skipping recent signal: {}", signal.panelTitle());
            return List.of();
        }

        // Query Prometheus
        List<PrometheusClient.TimeSeries> prometheusSeries = prometheusClient.queryRange(
                signal.query(),
                startTime,
                endTime,
                prometheusQueryStep
        );

        if (prometheusSeries.isEmpty()) {
            log.debug("[MonitoringCopilot] No Prometheus data for signal: {}", signal.panelTitle());
            return List.of();
        }

        // Convert Prometheus TimeSeries to internal TimeSeries model
        List<TimeSeries> timeSeriesList = convertTimeSeries(prometheusSeries);

        // Run anomaly detection
        Optional<AnomalyEvent> anomaly = detector.detect(
                signal,
                timeSeriesList,
                now,
                null // Z-score config not enabled for scheduled detection
        );

        if (anomaly.isPresent()) {
            // Update dedup cache
            recentDetections.put(dedupKey, now);
            return List.of(anomaly.get());
        }

        return List.of();
    }

    /**
     * Convert Prometheus TimeSeries to internal model
     */
    private List<TimeSeries> convertTimeSeries(List<PrometheusClient.TimeSeries> prometheusSeries) {
        return prometheusSeries.stream()
                .map(promSeries -> {
                    String label = promSeries.metric().toString();

                    List<maple.expectation.monitoring.copilot.model.MetricPoint> points = promSeries.values().stream()
                            .map(vp -> maple.expectation.monitoring.copilot.model.MetricPoint.builder()
                                    .epochMillis(vp.timestamp() * 1000)
                                    .value(vp.getValueAsDouble())
                                    .build())
                            .toList();

                    return TimeSeries.builder()
                            .label(label)
                            .points(points)
                            .build();
                })
                .toList();
    }

    /**
     * Compose incident context and trigger AI analysis
     */
    private void processIncident(List<AnomalyEvent> anomalies, long now) {
        String incidentId = generateIncidentId(anomalies, now);

        IncidentContext context = IncidentContext.builder()
                .incidentId(incidentId)
                .summary(buildIncidentSummary(anomalies))
                .anomalies(anomalies)
                .evidence(List.of()) // Could be enhanced with PromQL snippets
                .metadata(buildIncidentMetadata(anomalies, now))
                .build();

        // Call AI analysis
        executor.executeVoid(
                () -> aiSreService.analyzeIncident(context),
                TaskContext.of("MonitoringCopilot", "AnalyzeIncident", incidentId)
        );

        // Send Discord notification
        sendDiscordNotification(context);
    }

    /**
     * Send Discord notification for incident
     */
    private void sendDiscordNotification(IncidentContext context) {
        executor.executeVoid(
                () -> {
                    String severity = determineOverallSeverity(context.anomalies());
                    List<DiscordNotifier.AnnotatedSignal> annotatedSignals = annotateSignals(context.anomalies());

                    String message = discordNotifier.formatIncidentMessage(
                            context.incidentId(),
                            severity,
                            annotatedSignals,
                            List.of(), // Hypotheses would come from AI analysis
                            List.of()  // Actions would come from AI analysis
                    );

                    discordNotifier.send(message);
                },
                TaskContext.of("MonitoringCopilot", "DiscordNotification", context.incidentId())
        );
    }

    /**
     * Annotate anomalies with signal definitions for Discord formatter
     */
    private List<DiscordNotifier.AnnotatedSignal> annotateSignals(List<AnomalyEvent> anomalies) {
        return anomalies.stream()
                .filter(a -> a.currentValue() != null)
                .map(a -> {
                    // Find signal definition from catalog
                    SignalDefinition signal = signalCatalogCache.stream()
                            .filter(s -> s.id().equals(a.signalId()))
                            .findFirst()
                            .orElse(SignalDefinition.builder()
                                    .id(a.signalId())
                                    .panelTitle("Unknown Signal")
                                    .build());

                    return new DiscordNotifier.AnnotatedSignal(signal, a.currentValue());
                })
                .toList();
    }

    /**
     * Generate unique incident ID
     */
    private String generateIncidentId(List<AnomalyEvent> anomalies, long now) {
        StringJoiner joiner = new StringJoiner("-");
        for (AnomalyEvent anomaly : anomalies) {
            joiner.add(anomaly.signalId());
        }
        return "INC-" + Math.abs(joiner.toString().hashCode()) + "-" + (now / 1000);
    }

    /**
     * Build incident summary text
     */
    private String buildIncidentSummary(List<AnomalyEvent> anomalies) {
        return String.format("Detected %d anomalous signals requiring attention", anomalies.size());
    }

    /**
     * Build incident metadata
     */
    private Map<String, Object> buildIncidentMetadata(List<AnomalyEvent> anomalies, long now) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("detectedAt", now);
        metadata.put("anomalyCount", anomalies.size());
        metadata.put("severity", determineOverallSeverity(anomalies));
        return metadata;
    }

    /**
     * Determine overall incident severity
     */
    private String determineOverallSeverity(List<AnomalyEvent> anomalies) {
        boolean hasCritical = anomalies.stream().anyMatch(a -> "CRITICAL".equals(a.severity()));
        return hasCritical ? "CRITICAL" : "WARNING";
    }

    /**
     * Cleanup stale dedup cache entries
     */
    private void cleanupDedupCache(long now) {
        long cutoff = now - dedupWindowMinutes * 60 * 1000;

        recentDetections.entrySet().removeIf(entry -> {
            boolean isStale = entry.getValue() < cutoff;
            if (isStale) {
                log.debug("[MonitoringCopilot] Cleaning up stale dedup entry: {}", entry.getKey());
            }
            return isStale;
        });
    }
}
