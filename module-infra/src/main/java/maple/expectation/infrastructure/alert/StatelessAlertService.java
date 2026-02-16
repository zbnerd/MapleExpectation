package maple.expectation.infrastructure.alert;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.port.AlertPublisher;
import maple.expectation.infrastructure.alert.channel.AlertChannel;
import maple.expectation.infrastructure.alert.message.AlertMessage;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stateless Alert Service
 *
 * <p>DIP (Dependency Inversion): Implements {@link AlertPublisher} interface from module-core
 *
 * <p>SRP: Single responsibility - orchestrate alert sending
 *
 * <h4>Architecture Decision:</h4>
 *
 * <ul>
 *   <li>Uses AlertChannelStrategy to select channel based on priority
 *   <li>CRITICAL alerts bypass all stateful dependencies (Redis/DB)
 *   <li>Protected by LogicExecutor for exception handling
 *   <li>Returns immediately (fire-and-forget for non-blocking)
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@Service
@Slf4j
public class StatelessAlertService implements AlertPublisher {

  private final maple.expectation.infrastructure.alert.strategy.AlertChannelStrategy
      channelStrategy;
  private final LogicExecutor executor;
  private final String discordWebhookUrl;

  public StatelessAlertService(
      maple.expectation.infrastructure.alert.strategy.AlertChannelStrategy channelStrategy,
      LogicExecutor executor,
      @Value("${alert.discord.webhook-url:}") String discordWebhookUrl) {
    this.channelStrategy = channelStrategy;
    this.executor = executor;
    this.discordWebhookUrl = discordWebhookUrl;
  }

  /**
   * Send CRITICAL alert - Stateless, no Redis/DB dependency
   *
   * @param title Alert title
   * @param message Alert message
   * @param error Throwable (optional)
   */
  @Override
  public void sendCritical(String title, String message, Throwable error) {
    AlertChannel channel = channelStrategy.getChannel(AlertPriority.CRITICAL);
    executor.executeVoid(
        () -> {
          boolean sent = channel.send(new AlertMessage(title, message, error, discordWebhookUrl));
          if (!sent && log.isWarnEnabled()) {
            log.warn("[StatelessAlertService] Failed to send critical alert: {}", title);
          }
        },
        TaskContext.of("AlertService", "Critical", title));
  }

  /**
   * Send NORMAL alert - can use throttling
   *
   * @param title Alert title
   * @param message Alert message
   */
  public void sendNormal(String title, String message) {
    AlertChannel channel = channelStrategy.getChannel(AlertPriority.NORMAL);
    executor.executeVoid(
        () -> {
          channel.send(new AlertMessage(title, message, null, discordWebhookUrl));
        },
        TaskContext.of("AlertService", "Normal", title));
  }
}
