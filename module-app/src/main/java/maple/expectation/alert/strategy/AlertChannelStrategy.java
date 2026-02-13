package maple.expectation.alert.strategy;

import maple.expectation.alert.AlertPriority;
import maple.expectation.alert.channel.AlertChannel;

/**
 * Alert Channel Strategy Interface
 *
 * <p>Strategy pattern for selecting appropriate alert channel
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
public interface AlertChannelStrategy {

  /**
   * Get alert channel based on priority
   *
   * @param priority Alert priority (CRITICAL, NORMAL, BACKGROUND)
   * @return Alert channel implementation
   */
  AlertChannel getChannel(AlertPriority priority);
}
