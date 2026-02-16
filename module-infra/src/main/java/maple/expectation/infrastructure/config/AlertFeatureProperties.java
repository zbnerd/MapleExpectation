package maple.expectation.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Alert Feature Flags Configuration
 *
 * <p>Feature flags for gradual rollout of stateless alert system:
 *
 * <ul>
 *   <li>alert.stateless.enabled: Master switch for stateless alert system (default: true)
 *   <li>alert.stateless.fallback-to-file: Enable file fallback when Discord fails (default: true)
 *   <li>alert.in-memory.capacity: Maximum alerts stored in memory before eviction (default: 1000)
 * </ul>
 *
 * @see maple.expectation.alert.DiscordAlertChannel
 * @see maple.expectation.config.AlertWebClientConfig
 */
@Data
@Component
@ConfigurationProperties(prefix = "alert")
public class AlertFeatureProperties {

  /**
   * Stateless alert system master switch. When false, all alert operations are disabled
   * immediately.
   */
  private Stateless stateless = new Stateless();

  /**
   * In-memory buffer capacity for alert queue. Alerts exceeding this capacity will be evicted using
   * LRU policy.
   */
  private InMemory inMemory = new InMemory();

  /**
   * File-based alert persistence configuration. Used as fallback when Discord API is unavailable.
   */
  private File file = new File();

  @Data
  public static class Stateless {
    /** Master switch for stateless alert system. Default: true (enabled) */
    private boolean enabled = true;

    /** Enable fallback to file logging when Discord API fails. Default: true (enabled) */
    private boolean fallbackToFile = true;
  }

  @Data
  public static class InMemory {
    /** Maximum capacity of in-memory alert buffer. Default: 1000 alerts */
    private int capacity = 1000;
  }

  @Data
  public static class File {
    /** Path to file-based alert log. Default: /var/log/maple-alerts.log */
    private String path = "/var/log/maple-alerts.log";
  }
}
