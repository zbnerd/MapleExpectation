package maple.expectation.infrastructure.alert.strategy;

import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import maple.expectation.infrastructure.alert.AlertPriority;
import maple.expectation.infrastructure.alert.channel.AlertChannel;
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

  /**
   * Bean name for test compatibility.
   *
   * <p>Tests may reference this bean by name. Using @Component ensures the bean is always
   * available, even when alert.stateless.enabled=false.
   */
  public static final String BEAN_NAME = "statelessAlertChannelStrategy";

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
