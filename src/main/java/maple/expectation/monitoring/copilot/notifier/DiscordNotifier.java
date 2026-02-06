package maple.expectation.monitoring.copilot.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.InternalSystemException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.monitoring.copilot.model.SignalDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Discord Webhook Notifier for Monitoring Copilot
 *
 * <p>Sends formatted incident alerts to Discord with:
 * <ul>
 *   <li>Severity-based emoji prefix (🚨 for CRIT, ⚠️ for WARN)</li>
 *   <li>Incident ID and severity level</li>
 *   <li>Top 3 anomalous signals with values</li>
 *   <li>LLM-generated hypotheses (top 2)</li>
 *   <li>Proposed remediation actions (top 2)</li>
 *   <li>Evidence section with PromQL queries</li>
 * </ul>
 *
 * <h3>Rate Limit Handling</h3>
 * <p>Implements single retry on 429 (Too Many Requests) responses</p>
 *
 * <h3>CLAUDE.md Compliance</h3>
 * <ul>
 *   <li>Section 12: All exceptions handled via LogicExecutor</li>
 *   <li>No try-catch blocks in business logic</li>
 * </ul>
 *
 * @see <a href="https://discord.com/developers/docs/resources/webhook">Discord Webhook API</a>
 */
@Slf4j
@Component
public class DiscordNotifier {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String CONTENT_TYPE = "application/json";
    private static final int MAX_RETRIES = 1;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final LogicExecutor executor;

    @Value("${app.monitoring.discord.webhook-url:}")
    private String webhookUrl;

    public DiscordNotifier(HttpClient httpClient, ObjectMapper objectMapper, LogicExecutor executor) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /**
     * Send incident alert to Discord webhook
     *
     * @param content Pre-formatted incident message content
     */
    public void send(String content) {
        executor.executeVoid(
                () -> sendInternal(content),
                TaskContext.of("DiscordNotifier", "SendWebhook")
        );
    }

    /**
     * Internal send implementation with checked exceptions.
     * Wrapped by LogicExecutor for proper exception handling.
     */
    private void sendInternal(String content) throws Exception {
        DiscordWebhookPayload payload = new DiscordWebhookPayload(content);
        String jsonPayload = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", CONTENT_TYPE)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = sendWithRetry(request, 0);

        if (response.statusCode() >= 400) {
            log.error("[DiscordNotifier] Failed to send webhook: HTTP {} - {}",
                    response.statusCode(), response.body());
            throw new InternalSystemException(
                    String.format("Discord webhook failed: HTTP %d - %s",
                            response.statusCode(), response.body())
            );
        }

        log.info("[DiscordNotifier] Incident alert sent successfully");
    }

    /**
     * Send HTTP request with retry logic for rate limiting (429)
     *
     * @param request HTTP request to send
     * @param attempt Current attempt number
     * @return HTTP response
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request, int attempt) {
        HttpResponse<String> response = sendHttpRequest(request);

        // Handle rate limit (429) - retry once
        if (response.statusCode() == 429 && attempt < MAX_RETRIES) {
            log.warn("[DiscordNotifier] Rate limited (429), retrying... (attempt {}/{})",
                    attempt + 1, MAX_RETRIES + 1);

            long delayMs = extractRetryAfter(response);
            sleep(delayMs);

            return sendWithRetry(request, attempt + 1);
        }

        return response;
    }

    /**
     * Send HTTP request with exception translation.
     */
    private HttpResponse<String> sendHttpRequest(HttpRequest request) {
        try {
            return httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new InternalSystemException("Discord webhook request interrupted", e);
            }
            throw new InternalSystemException(
                    String.format("Discord webhook HTTP request failed: %s", e.getMessage()), e);
        }
    }

    /**
     * Extract retry delay from Retry-After header
     *
     * @param response HTTP response with 429 status
     * @return Delay in milliseconds
     */
    private long extractRetryAfter(HttpResponse<String> response) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        if (retryAfter != null) {
            try {
                int seconds = Integer.parseInt(retryAfter);
                return seconds * 1000L;
            } catch (NumberFormatException e) {
                log.debug("[DiscordNotifier] Invalid Retry-After header: {}", retryAfter);
            }
        }
        return 1000L; // Default 1 second
    }

    /**
     * Sleep with interrupt handling.
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternalSystemException("Sleep interrupted", e);
        }
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
            List<String> actions
    ) {
        StringBuilder sb = new StringBuilder();

        // Header with emoji prefix
        String emoji = "CRIT".equals(severity) ? "🚨" : "⚠️";
        sb.append(String.format("%s **INCIDENT ALERT** `%s` [%s]\n\n", emoji, incidentId, severity));

        // Section 1: Top 3 Anomalous Signals
        sb.append("**📊 Top Anomalous Signals**\n");
        int signalCount = Math.min(3, signals.size());
        for (int i = 0; i < signalCount; i++) {
            AnnotatedSignal signal = signals.get(i);
            sb.append(String.format("%d. **%s**: `%.4f` %s\n",
                    i + 1,
                    signal.signal().panelTitle(),
                    signal.value(),
                    signal.signal().unit() != null ? signal.signal().unit() : ""));
        }
        sb.append("\n");

        // Section 2: LLM Hypotheses (Top 2)
        if (!hypotheses.isEmpty()) {
            sb.append("**🤖 AI Hypotheses**\n");
            int hypCount = Math.min(2, hypotheses.size());
            for (int i = 0; i < hypCount; i++) {
                sb.append(String.format("%d. %s\n", i + 1, hypotheses.get(i)));
            }
            sb.append("\n");
        }

        // Section 3: Proposed Actions (Top 2)
        if (!actions.isEmpty()) {
            sb.append("**🔧 Proposed Actions**\n");
            int actionCount = Math.min(2, actions.size());
            for (int i = 0; i < actionCount; i++) {
                sb.append(String.format("%d. %s\n", i + 1, actions.get(i)));
            }
            sb.append("\n");
        }

        // Section 4: Evidence (PromQL)
        sb.append("**📋 Evidence (PromQL)**\n");
        for (int i = 0; i < signalCount; i++) {
            AnnotatedSignal signal = signals.get(i);
            String query = signal.signal().query();
            if (query != null && !query.isBlank()) {
                String truncatedQuery = query.length() > 100
                        ? query.substring(0, 97) + "..."
                        : query;
                sb.append(String.format("- `%s`\n", truncatedQuery));
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
    public record AnnotatedSignal(SignalDefinition signal, double value) {
    }
}
