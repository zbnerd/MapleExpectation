package maple.expectation.alert.channel;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.alert.factory.MessageFactory;
import maple.expectation.alert.message.AlertMessage;
import maple.expectation.config.AlertFeatureProperties;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * @author ADR-0345
 * @since 2025-02-12
 */
@Component
@ConditionalOnProperty(
    name = "alert.stateless.enabled",
    havingValue = "true",
    matchIfMissing = true)
@Slf4j
public class DiscordAlertChannel implements AlertChannel {

  private final WebClient alertWebClient;
  private final LogicExecutor executor;
  private final AlertFeatureProperties alertFeatureProperties;

  @Autowired
  public DiscordAlertChannel(
      @Qualifier("alertWebClient") WebClient alertWebClient,
      LogicExecutor executor,
      AlertFeatureProperties alertFeatureProperties) {
    this.alertWebClient = alertWebClient;
    this.executor = executor;
    this.alertFeatureProperties = alertFeatureProperties;
  }

  @Override
  public boolean send(AlertMessage message) {
    // Check feature flag before sending
    if (!alertFeatureProperties.getStateless().isEnabled()) {
      log.debug("[DiscordAlertChannel] Alert system disabled via feature flag");
      return false;
    }

    return executor.executeOrDefault(
        () -> {
          try {
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

          } catch (WebClientRequestException e) {
            log.warn("[DiscordAlertChannel] Discord webhook request failed: {}", e.getMessage());
            return false;
          } catch (Exception e) {
            log.error(
                "[DiscordAlertChannel] Unexpected error sending alert: {}", e.getMessage(), e);
            return false;
          }
        },
        false, // Fallback: failed
        TaskContext.of("AlertChannel", "Discord", message.getTitle()));
  }

  @Override
  public String getChannelName() {
    return "discord";
  }
}
