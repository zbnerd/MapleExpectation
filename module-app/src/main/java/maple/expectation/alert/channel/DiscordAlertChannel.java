package maple.expectation.alert.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.alert.factory.MessageFactory;
import maple.expectation.alert.message.AlertMessage;
import maple.expectation.config.AlertFeatureProperties;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

/**
 * Discord Alert Channel Implementation
 *
 * <p>Primary alert channel using Discord webhook
 *
 * <p>Uses dedicated alertWebClient bean (isolated from Nexon API)
 *
 * <h4>Architecture Decision:</h4>
 *
 * <ul>
 *   <li>Uses dedicated WebClient bean to avoid resource contention
 *   <li>Implements AlertChannel interface (SRP)
 *   <li>Protected by LogicExecutor for exception handling
 *   <li>Non-blocking: Returns immediately after queueing send
 *   <li>Feature flag controlled via alert.stateless.enabled
 * </ul>
 *
 * <h3>CLAUDE.md Section 12 Compliance</h3>
 *
 * <ul>
 *   <li>Uses LogicExecutor.executeWithFallback() for WebClientRequestException handling
 *   <li>No raw try-catch blocks in business logic
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@Component
@ConditionalOnProperty(
    name = "alert.stateless.enabled",
    havingValue = "true",
    matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class DiscordAlertChannel implements AlertChannel {

  private final WebClient alertWebClient;
  private final LogicExecutor executor;
  private final AlertFeatureProperties alertFeatureProperties;

  @Override
  public boolean send(AlertMessage message) {
    // Check feature flag before sending
    if (!alertFeatureProperties.getStateless().isEnabled()) {
      log.debug("[DiscordAlertChannel] Alert system disabled via feature flag");
      return false;
    }

    return executor.executeWithFallback(
        () -> sendToDiscord(message),
        e -> handleWebClientException(message, e),
        TaskContext.of("AlertChannel", "Discord", message.getTitle()));
  }

  /**
   * Send alert to Discord webhook.
   *
   * <p>Wrapped by LogicExecutor.executeWithFallback() which handles WebClientRequestException with
   * logging and returns false.
   */
  private boolean sendToDiscord(AlertMessage message) {
    ResponseEntity<Void> response =
        alertWebClient
            .post()
            .uri(message.getWebhookUrl())
            .bodyValue(MessageFactory.toDiscordPayload(message))
            .retrieve()
            .toBodilessEntity()
            .block();

    boolean success = response.getStatusCode().is2xxSuccessful();

    if (success && log.isInfoEnabled()) {
      log.info(
          "[DiscordAlertChannel] Alert sent successfully to {}: {}",
          message.getTitle(),
          response.getStatusCode());
    } else if (!success && log.isWarnEnabled()) {
      log.warn(
          "[DiscordAlertChannel] Alert failed with status {}: {}",
          message.getTitle(),
          response.getStatusCode());
    }

    return success;
  }

  /**
   * Recovery handler for WebClient exceptions.
   *
   * <p>Called by LogicExecutor.executeWithFallback() when an exception occurs.
   */
  private boolean handleWebClientException(AlertMessage message, Throwable e) {
    if (e instanceof WebClientRequestException) {
      log.warn("[DiscordAlertChannel] Discord webhook request failed: {}", e.getMessage());
    } else {
      log.error("[DiscordAlertChannel] Unexpected error sending alert: {}", e.getMessage(), e);
    }
    return false;
  }

  @Override
  public String getChannelName() {
    return "discord";
  }
}
