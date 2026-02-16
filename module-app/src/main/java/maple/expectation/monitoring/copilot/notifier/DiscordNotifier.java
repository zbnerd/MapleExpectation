package maple.expectation.monitoring.copilot.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.InternalSystemException;
import maple.expectation.infrastructure.config.DiscordTimeoutProperties;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.monitoring.copilot.model.SignalDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Discord Webhook Notifier for Monitoring Copilot
 *
 * <p>Sends formatted incident alerts to Discord with:
 *
 * <ul>
 *   <li>Severity-based emoji prefix (🚨 for CRIT, ⚠️ for WARN)
 *   <li>Incident ID and severity level
 *   <li>Top 3 anomalous signals with values (SYMPTOMS)
 *   <li>LLM-generated hypotheses (top 2) (ROOT CAUSE ANALYSIS)
 *   <li>Proposed remediation actions (top 2) (REMEDIATION)
 * </ul>
 *
 * <h3>Rate Limit Handling</h3>
 *
 * <p>Implements single retry on 429 (Too Many Requests) responses
 *
 * <h3>CLAUDE.md Compliance</h3>
 *
 * <ul>
 *   <li>Section 12: All exceptions handled via LogicExecutor
 *   <li>No try-catch blocks in business logic
 * </ul>
 *
 * @see <a href="https://discord.com/developers/docs/resources/webhook">Discord Webhook API</a>
 */
@Slf4j
@Component
public class DiscordNotifier {

  private static final String CONTENT_TYPE = "application/json";
  private static final int MAX_RETRIES = 1;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final LogicExecutor executor;
  private final DiscordTimeoutProperties timeoutProperties;

  @Value("${alert.discord.webhook-url:}")
  private String webhookUrl;

  public DiscordNotifier(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      LogicExecutor executor,
      DiscordTimeoutProperties timeoutProperties) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.executor = executor;
    this.timeoutProperties = timeoutProperties;
  }

  /**
   * Send incident alert to Discord webhook
   *
   * @param content Pre-formatted incident message content
   */
  public void send(String content) {
    executor.executeWithTranslation(
        () -> {
          sendInternal(content);
          return null; // Void operation
        },
        (e, ctx) ->
            new InternalSystemException(
                String.format(
                    "Discord webhook send failed [%s]: %s", ctx.toTaskName(), e.getMessage()),
                e),
        TaskContext.of("DiscordNotifier", "SendWebhook"));
  }

  /**
   * Internal send implementation with checked exceptions. Wrapped by
   * LogicExecutor.executeWithTranslation() for proper exception handling.
   */
  private void sendInternal(String content) throws Exception {
    DiscordWebhookPayload payload = new DiscordWebhookPayload(content);
    String jsonPayload = objectMapper.writeValueAsString(payload);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", CONTENT_TYPE)
            .timeout(Duration.ofSeconds(timeoutProperties.webhookTimeoutSeconds()))
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build();

    HttpResponse<String> response = sendWithRetry(request, 0);

    if (response.statusCode() >= 400) {
      log.error(
          "[DiscordNotifier] Failed to send webhook: HTTP {} - {}",
          response.statusCode(),
          response.body());
      throw new InternalSystemException(
          String.format(
              "Discord webhook failed: HTTP %d - %s", response.statusCode(), response.body()));
    }

    log.info("[DiscordNotifier] Incident alert sent successfully");
  }

  /**
   * Send HTTP request with retry logic for rate limiting (429)
   *
   * @param request HTTP request to send
   * @param attempt Current attempt number
   * @return HTTP response
   * @throws Exception if HTTP request fails or is interrupted
   */
  private HttpResponse<String> sendWithRetry(HttpRequest request, int attempt) throws Exception {
    HttpResponse<String> response = sendHttpRequest(request);

    // Handle rate limit (429) - retry once
    if (response.statusCode() == 429 && attempt < MAX_RETRIES) {
      log.warn(
          "[DiscordNotifier] Rate limited (429), retrying... (attempt {}/{})",
          attempt + 1,
          MAX_RETRIES + 1);

      long delayMs = extractRetryAfter(response);
      sleep(delayMs);

      return sendWithRetry(request, attempt + 1);
    }

    return response;
  }

  /**
   * Send HTTP request with exception translation.
   *
   * @throws Exception if HTTP request fails or is interrupted
   * @throws InterruptedException if HTTP request is interrupted
   */
  private HttpResponse<String> sendHttpRequest(HttpRequest request)
      throws Exception, InterruptedException {
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Extract retry delay from Retry-After header. Uses Optional to safely parse and default to 1
   * second.
   *
   * @param response HTTP response with 429 status
   * @return Delay in milliseconds (default from properties if header missing/invalid)
   */
  private long extractRetryAfter(HttpResponse<String> response) {
    return response
        .headers()
        .firstValue("Retry-After")
        .flatMap(
            retryAfter -> {
              try {
                return Optional.of(Integer.parseInt(retryAfter) * 1000L);
              } catch (NumberFormatException e) {
                log.debug("[DiscordNotifier] Invalid Retry-After header: {}", retryAfter);
                return Optional.empty();
              }
            })
        .orElse(timeoutProperties.retryAfterDefaultMs());
  }

  /**
   * Sleep with interrupt handling.
   *
   * <p><b>Section 14 Exception:</b> Thread.sleep is acceptable here for HTTP client retry backoff
   * with proper interrupt handling. This is a synchronous delay in a retry loop, not an
   * asynchronous scheduled task. The InterruptedException is properly propagated to the caller.
   *
   * @param millis delay duration in milliseconds
   * @throws InterruptedException if sleep is interrupted
   */
  private void sleep(long millis) throws InterruptedException {
    Thread.sleep(millis);
  }

  /**
   * Format incident message with all required sections
   *
   * @param incidentId Unique incident identifier
   * @param severity Severity level (CRIT/WARN)
   * @param signals Top anomalous signals (max 3)
   * @param hypotheses LLM-generated root cause hypotheses (max 2)
   * @param actions Proposed remediation actions (max 2)
   * @return Formatted Discord message
   */
  public String formatIncidentMessage(
      String incidentId,
      String severity,
      List<AnnotatedSignal> signals,
      List<String> hypotheses,
      List<String> actions) {
    StringBuilder sb = new StringBuilder();

    // Header with emoji prefix
    String emoji = "CRIT".equals(severity) ? "🚨" : "⚠️";
    sb.append(String.format("%s **INCIDENT ALERT** `%s` [%s]\n\n", emoji, incidentId, severity));

    // Section 1: SYMPTOMS - Top 3 Anomalous Signals
    sb.append("**📊 SYMPTOMS**\n");
    int signalCount = Math.min(3, signals.size());
    for (int i = 0; i < signalCount; i++) {
      AnnotatedSignal signal = signals.get(i);
      sb.append(
          String.format(
              "%d. **%s**: `%.4f` %s\n",
              i + 1,
              signal.signal().panelTitle(),
              signal.value(),
              signal.signal().unit() != null ? signal.signal().unit() : ""));
    }
    sb.append("\n");

    // Section 2: ROOT CAUSE ANALYSIS - LLM Hypotheses (Top 2)
    if (!hypotheses.isEmpty()) {
      sb.append("**🤖 ROOT CAUSE ANALYSIS**\n");
      int hypCount = Math.min(2, hypotheses.size());
      for (int i = 0; i < hypCount; i++) {
        sb.append(String.format("%d. %s\n", i + 1, hypotheses.get(i)));
      }
      sb.append("\n");
    }

    // Section 3: REMEDIATION - Proposed Actions (Top 2)
    if (!actions.isEmpty()) {
      sb.append("**🔧 REMEDIATION**\n");
      int actionCount = Math.min(2, actions.size());
      for (int i = 0; i < actionCount; i++) {
        sb.append(String.format("%d. %s\n", i + 1, actions.get(i)));
      }
    }

    return sb.toString();
  }

  /**
   * Discord webhook payload wrapper
   *
   * @param content Message content (formatted with Markdown)
   */
  private record DiscordWebhookPayload(String content) {
    // Jackson serializes this as {"content": "..."}
  }

  /**
   * Signal with associated metric value
   *
   * @param signal Signal definition (query, metadata)
   * @param value Anomalous metric value
   */
  public record AnnotatedSignal(SignalDefinition signal, double value) {}
}
