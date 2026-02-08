package maple.expectation.monitoring.copilot.pipeline;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.monitoring.ai.AiSreService;
import maple.expectation.monitoring.copilot.model.*;
import maple.expectation.monitoring.copilot.notifier.DiscordNotifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Alert Notification Service (Issue #251)
 *
 * <h3>Responsibility</h3>
 *
 * <p>Manages Discord alert notifications with throttling and formatting:
 *
 * <pre>
 * 1. De-duplication Check → Prevent alert spam
 * 2. AI SRE Analysis → Generate mitigation plan
 * 3. Message Formatting → Discord-compatible format
 * 4. Webhook Delivery → Send to Discord
 * </pre>
 *
 * <h3>Throttling Strategy</h3>
 *
 * <p>Implements time-based de-duplication with configurable window (default 5 minutes):
 *
 * <ul>
 *   <li>Tracks recent incident IDs in memory
 *   <li>Cleaned up periodically to prevent memory leaks
 *   <li>Prevents duplicate alerts for same incident signature
 * </ul>
 *
 * <h3>Alert Formatting</h3>
 *
 * <p>Formats alerts using {@link DiscordNotifier} with:
 *
 * <ul>
 *   <li>Incident ID and severity
 *   <li>Annotated signal details (top 3)
 *   <li>AI-generated hypotheses (top 2)
 *   <li>Recommended actions (top 2)
 * </ul>
 *
 * <h3>LogicExecutor Compliance</h3>
 *
 * <p>All webhook calls wrapped in {@link LogicExecutor} for resilience and observability (Section
 * 12).
 *
 * @see DiscordNotifier
 * @see AiSreService
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monitoring.copilot.enabled", havingValue = "true")
public class AlertNotificationService {

  private final DiscordNotifier discordNotifier;
  private final LogicExecutor executor;

  @Value("${monitoring.copilot.alert.throttle-window-ms:300000}")
  private long throttleWindowMs;

  // De-duplication: Recent incident IDs (last 5 minutes by default)
  private final Map<String, Long> recentIncidents = new ConcurrentHashMap<>();

  /**
   * Send alert notification for detected incident.
   *
   * <p>Checks de-duplication cache, performs AI analysis (if available), formats message, and sends
   * to Discord webhook.
   *
   * @param context Incident context with anomalies and metadata
   * @param aiSreService Optional AI SRE service for analysis
   * @param signalDefinitions Signal definitions for annotation
   */
  public void sendAlert(
      IncidentContext context,
      Optional<AiSreService> aiSreService,
      List<SignalDefinition> signalDefinitions) {
    long now = System.currentTimeMillis();

    // 1. Clean old incidents
    cleanOldIncidents(now);

    // 2. De-duplication check
    if (isRecentIncident(context.incidentId(), now)) {
      log.info(
          "[AlertNotificationService] Incident {} already recent, skipping", context.incidentId());
      return;
    }

    // 3. Track incident IMMEDIATELY to prevent race conditions
    trackIncident(context.incidentId(), now);

    // 4. AI SRE analysis
    AiSreService.MitigationPlan plan =
        aiSreService
            .map(service -> service.analyzeIncident(context))
            .orElseGet(() -> createDefaultMitigationPlan(context));

    // 5. Send Discord alert
    sendDiscordAlert(context, plan, signalDefinitions);
  }

  /**
   * Send alert notification with pre-computed mitigation plan.
   *
   * <p>Useful when AI analysis is performed separately or cached.
   *
   * @param context Incident context
   * @param plan Mitigation plan (from AI or default)
   * @param signalDefinitions Signal definitions for annotation
   */
  public void sendAlertWithPlan(
      IncidentContext context,
      AiSreService.MitigationPlan plan,
      List<SignalDefinition> signalDefinitions) {
    long now = System.currentTimeMillis();

    // Clean and check de-duplication
    cleanOldIncidents(now);

    if (isRecentIncident(context.incidentId(), now)) {
      log.info(
          "[AlertNotificationService] Incident {} already recent, skipping", context.incidentId());
      return;
    }

    trackIncident(context.incidentId(), now);
    sendDiscordAlert(context, plan, signalDefinitions);
  }

  /**
   * Force send alert bypassing de-duplication cache.
   *
   * <p>Useful for manual alert triggering or critical alerts that must be delivered.
   *
   * @param context Incident context
   * @param plan Mitigation plan
   * @param signalDefinitions Signal definitions for annotation
   */
  public void forceSendAlert(
      IncidentContext context,
      AiSreService.MitigationPlan plan,
      List<SignalDefinition> signalDefinitions) {
    log.info("[AlertNotificationService] Force sending alert: {}", context.incidentId());
    sendDiscordAlert(context, plan, signalDefinitions);
  }

  /** Send Discord alert with formatted message */
  private void sendDiscordAlert(
      IncidentContext context,
      AiSreService.MitigationPlan plan,
      List<SignalDefinition> signalDefinitions) {
    executor.executeVoid(
        () -> {
          // Prepare annotated signals
          Map<String, SignalDefinition> signalMap =
              signalDefinitions.stream()
                  .collect(HashMap::new, (map, s) -> map.put(s.id(), s), HashMap::putAll);

          List<DiscordNotifier.AnnotatedSignal> annotatedSignals =
              context.anomalies().stream()
                  .limit(3)
                  .map(
                      anomaly ->
                          new DiscordNotifier.AnnotatedSignal(
                              signalMap.get(anomaly.signalId()), anomaly.currentValue()))
                  .toList();

          // Format hypotheses and actions
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

          // Determine severity
          String severity =
              context.anomalies().stream().anyMatch(a -> "CRIT".equals(a.severity()))
                  ? "CRIT"
                  : "WARN";

          // Format and send
          String message =
              discordNotifier.formatIncidentMessage(
                  context.incidentId(), severity, annotatedSignals, hypotheses, actions);

          discordNotifier.send(message);

          log.info("[AlertNotificationService] Alert sent: {}", context.incidentId());
        },
        TaskContext.of("AlertNotificationService", "SendDiscord", context.incidentId()));
  }

  /** Check if incident is recent (within throttle window) */
  private boolean isRecentIncident(String incidentId, long now) {
    Long timestamp = recentIncidents.get(incidentId);
    if (timestamp == null) {
      return false;
    }

    long age = now - timestamp;
    return age < throttleWindowMs;
  }

  /** Track incident to prevent duplicates */
  private void trackIncident(String incidentId, long timestamp) {
    recentIncidents.put(incidentId, timestamp);
    log.debug("[AlertNotificationService] Tracked incident: {}", incidentId);
  }

  /** Clean old incidents (older than throttle window) */
  private void cleanOldIncidents(long now) {
    long threshold = now - throttleWindowMs;

    // removeIf returns boolean, so we track count manually
    AtomicInteger removedCount = new AtomicInteger(0);
    recentIncidents
        .entrySet()
        .removeIf(
            entry -> {
              boolean isOld = entry.getValue() < threshold;
              if (isOld) {
                log.debug("[AlertNotificationService] Cleaned old incident: {}", entry.getKey());
                removedCount.incrementAndGet();
              }
              return isOld;
            });

    if (removedCount.get() > 0) {
      log.debug("[AlertNotificationService] Cleaned {} old incidents", removedCount.get());
    }
  }

  /**
   * Get current de-duplication cache size.
   *
   * @return Number of tracked incidents
   */
  public int getCacheSize() {
    return recentIncidents.size();
  }

  /** Clear all tracked incidents (useful for testing or manual reset). */
  public void clearCache() {
    int size = recentIncidents.size();
    recentIncidents.clear();
    log.info("[AlertNotificationService] Cleared {} tracked incidents", size);
  }

  /** Create default mitigation plan when AI SRE is not available */
  private AiSreService.MitigationPlan createDefaultMitigationPlan(IncidentContext context) {
    log.warn("[AlertNotificationService] AI SRE not available, using default mitigation plan");

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
