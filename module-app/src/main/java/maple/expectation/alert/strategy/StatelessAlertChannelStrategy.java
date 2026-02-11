package maple.expectation.alert.strategy;

import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import maple.expectation.alert.AlertPriority;
import maple.expectation.alert.channel.AlertChannel;
import org.springframework.stereotype.Component;

/**
 * Stateless Alert Channel Strategy
 *
 * <p>OCP (Open/Closed Principle): New alert channels without modifying existing code
 *
 * <p>Selects appropriate channel based on alert priority
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@Component
@RequiredArgsConstructor
public class StatelessAlertChannelStrategy implements AlertChannelStrategy {

  private final Map<AlertPriority, Supplier<AlertChannel>> channelProviders;

  @Override
  public AlertChannel getChannel(AlertPriority priority) {
    return channelProviders.getOrDefault(priority, () -> getDefaultChannel()).get();
  }

  private AlertChannel getDefaultChannel() {
    // Default: Discord alert channel
    // Will be injected via constructor when config is ready
    throw new UnsupportedOperationException("Default channel not implemented yet - use Discord");
  }
}
