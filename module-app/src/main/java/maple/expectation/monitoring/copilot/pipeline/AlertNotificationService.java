package maple.expectation.monitoring.copilot.pipeline;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.monitoring.ai.AiSreService;
import maple.expectation.monitoring.copilot.model.*;
import maple.expectation.monitoring.copilot.notifier.DiscordNotifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Alert Notification Service (Issue #251)
 *
 * <h3>Responsibility</h3>
 *
 * <p>Manages Discord alert notifications with formatting and delivery:
 *
 * <pre>
 * 1. De-duplication Check → Prevent alert spam (via DeDuplicationCache)
 * 2. AI SRE Analysis → Generate mitigation plan
 * 3. Message Formatting → Discord-compatible format
 * 4. Webhook Delivery → Send to Discord
 * </pre>
 *
 * <h3>SRP Compliance</h3>
 *
 * <p>De-duplication logic extracted to {@link DeDuplicationCache} following Single Responsibility
 * Principle.
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
 * @see DeDuplicationCache
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monitoring.copilot.enabled", havingValue = "true")
public class AlertNotificationService {

  private final DiscordNotifier discordNotifier;
  private final LogicExecutor executor;
  private final DeDuplicationCache deDuplicationCache;

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
    deDuplicationCache.cleanOld(now);

    // 2. De-duplication check
    if (deDuplicationCache.isRecent(context.incidentId(), now)) {
      log.info(
          "[AlertNotificationService] Incident {} already recent, skipping", context.incidentId());
      return;
    }

    // 3. AI SRE analysis
    AiSreService.MitigationPlan plan =
        aiSreService
            .map(service -> service.analyzeIncident(context))
            .orElseGet(() -> createDefaultMitigationPlan(context));

    // 4. Send Discord alert (track incident only after success)
    sendDiscordAlert(context, plan, signalDefinitions, now);
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
    deDuplicationCache.cleanOld(now);

    if (deDuplicationCache.isRecent(context.incidentId(), now)) {
      log.info(
          "[AlertNotificationService] Incident {} already recent, skipping", context.incidentId());
      return;
    }

    // Send Discord alert (track incident only after success)
    sendDiscordAlert(context, plan, signalDefinitions, now);
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
    long now = System.currentTimeMillis();
    log.info("[AlertNotificationService] Force sending alert: {}", context.incidentId());
    sendDiscordAlert(context, plan, signalDefinitions, now);
  }

  /** Send Discord alert with formatted message */
  private void sendDiscordAlert(
      IncidentContext context,
      AiSreService.MitigationPlan plan,
      List<SignalDefinition> signalDefinitions,
      long timestamp) {
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
              context.anomalies().stream().anyMatch(a -> "CRITICAL".equals(a.severity()))
                  ? "CRIT"
                  : "WARN";

          // Format and send
          String message =
              discordNotifier.formatIncidentMessage(
                  context.incidentId(), severity, annotatedSignals, hypotheses, actions);

          discordNotifier.send(message);

          // Track incident ONLY after successful webhook delivery
          deDuplicationCache.track(context.incidentId(), timestamp);

          log.info("[AlertNotificationService] Alert sent: {}", context.incidentId());
        },
        TaskContext.of("AlertNotificationService", "SendDiscord", context.incidentId()));
  }

  /**
   * Get current de-duplication cache size.
   *
   * @return Number of tracked incidents
   */
  public int getCacheSize() {
    return deDuplicationCache.size();
  }

  /** Clear all tracked incidents (useful for testing or manual reset). */
  public void clearCache() {
    deDuplicationCache.clear();
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
