package maple.expectation.monitoring.copilot.scheduler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.monitoring.ai.AiSreService;
import maple.expectation.monitoring.copilot.dedup.SignalDeduplicationStrategy;
import maple.expectation.monitoring.copilot.model.AnomalyEvent;
import maple.expectation.monitoring.copilot.model.IncidentContext;
import maple.expectation.monitoring.copilot.model.SignalDefinition;
import maple.expectation.monitoring.copilot.pipeline.AlertNotificationService;
import maple.expectation.monitoring.copilot.pipeline.AnomalyDetectionOrchestrator;
import maple.expectation.monitoring.copilot.pipeline.SignalDefinitionLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monitoring Copilot Scheduler (Refactored for SRP - Issue #251)
 *
 * <h3>Architecture</h3>
 *
 * <p>Delegates responsibilities to specialized services:
 *
 * <ul>
 *   <li>{@link SignalDefinitionLoader} - Load signal catalog from Grafana JSON (cached 5min)
 *   <li>{@link AnomalyDetectionOrchestrator} - Coordinate Prometheus queries and detection
 *   <li>{@link AlertNotificationService} - Send Discord notifications with throttling
 * </ul>
 *
 * <h3>Remaining Responsibilities</h3>
 *
 * <ul>
 *   <li>Schedule orchestration (15-second intervals)
 *   <li>Signal prioritization (top N by priority score)
 *   <li>Deduplication coordination via {@link SignalDeduplicationStrategy}
 *   <li>Pipeline assembly and flow control
 * </ul>
 *
 * <h3>Execution Interval</h3>
 *
 * <p>Runs every 15 seconds via {@code @Scheduled(fixedRate = 15000)}
 *
 * <h3>Stateless Design</h3>
 *
 * <p>Deduplication uses {@link SignalDeduplicationStrategy} with PromQL re-query instead of
 * in-memory state, enabling horizontal scale-out without server-bound data.
 *
 * <h3>LogicExecutor Pattern</h3>
 *
 * <p>All operations wrapped in executor.executeOrDefault() for resilience and observability
 *
 * @see SignalDefinitionLoader
 * @see AnomalyDetectionOrchestrator
 * @see AlertNotificationService
 * @see SignalDeduplicationStrategy
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monitoring.copilot.enabled", havingValue = "true")
public class MonitoringCopilotScheduler {

  private final SignalDefinitionLoader signalLoader;
  private final AnomalyDetectionOrchestrator detectionOrchestrator;
  private final AlertNotificationService alertService;
  private final AiSreService aiSreService;
  private final LogicExecutor executor;
  private final SignalDeduplicationStrategy dedupStrategy;

  @Value("${monitoring.copilot.top-signals:10}")
  private int topSignalsCount;

  /**
   * Main scheduled task: runs every monitoring.interval-seconds (default 15 seconds)
   *
   * <p>Property is in seconds, converted to milliseconds for @Scheduled
   */
  @Scheduled(fixedRateString = "${monitoring.interval-seconds:15}000")
  public void monitorAndDetect() {
    TaskContext context = TaskContext.of("MonitoringCopilot", "ScheduledDetection");

    executor.executeVoid(
        () -> {
          long now = System.currentTimeMillis();

          // 1. Load signal catalog (with 5min cache)
          List<SignalDefinition> signals = signalLoader.loadSignalDefinitions();

          if (signals.isEmpty()) {
            log.debug("[MonitoringCopilot] No signals loaded, skipping detection cycle");
            return;
          }

          // 2. Select top N priority signals
          List<SignalDefinition> topSignals = selectTopPrioritySignals(signals);
          log.debug("[MonitoringCopilot] Selected {} top priority signals", topSignals.size());

          // 3. Run anomaly detection via orchestrator
          List<AnomalyEvent> detectedAnomalies =
              detectionOrchestrator.detectAnomalies(topSignals, now);

          if (detectedAnomalies.isEmpty()) {
            log.debug("[MonitoringCopilot] No anomalies detected in this cycle");
            return;
          }

          log.info("[MonitoringCopilot] Detected {} anomalies", detectedAnomalies.size());

          // 4. Compose incident context and send alert
          processIncident(detectedAnomalies, now);

          // 5. Cleanup stale dedup entries
          dedupStrategy.cleanup(now);
        },
        context);
  }

  /** Select top N priority signals based on metadata priority score */
  private List<SignalDefinition> selectTopPrioritySignals(List<SignalDefinition> signals) {
    return signals.stream()
        .filter(s -> s.metadata() != null && s.metadata().containsKey("priorityScore"))
        .sorted(
            (s1, s2) -> {
              int score1 = Integer.parseInt(s1.metadata().get("priorityScore"));
              int score2 = Integer.parseInt(s2.metadata().get("priorityScore"));
              return Integer.compare(score2, score1); // Descending order
            })
        .limit(topSignalsCount)
        .toList();
  }

  /** Compose incident context and trigger alert notification */
  private void processIncident(List<AnomalyEvent> anomalies, long now) {
    // Load fresh signal catalog for annotation
    List<SignalDefinition> signalCatalog = signalLoader.loadSignalDefinitions();

    // Build incident context using orchestrator
    IncidentContext context =
        detectionOrchestrator.buildIncidentContext(anomalies, signalCatalog, Map.of());

    // Send alert via notification service (includes AI analysis internally)
    alertService.sendAlert(context, Optional.of(aiSreService), signalCatalog);
  }
}
