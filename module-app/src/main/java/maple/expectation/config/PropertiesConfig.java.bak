package maple.expectation.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Unified properties configuration for P1 hardcoded value externalization.
 *
 * <h3>Enabled Properties</h3>
 *
 * <ul>
 *   <li>BatchProperties - Batch sizes across services
 *   <li>DiscordTimeoutProperties - Discord webhook timeouts
 *   <li>MonitoringThresholdProperties - Monitoring alert thresholds
 * </ul>
 *
 * <h3>Configuration in application.yml</h3>
 *
 * <pre>
 * expectation:
 *   batch:
 *     like-relation-sync-size: 100
 *     expectation-write-size: 100
 *     acl-writer-size: 1000
 *     mysql-fallback-sync-size: 100
 *   discord:
 *     webhook-timeout-seconds: 5
 *     retry-after-default-ms: 1000
 *   monitoring-threshold:
 *     buffer-saturation-count: 5000
 *     buffer-saturation-double: 5000.0
 * </pre>
 */
@Configuration
@EnableConfigurationProperties({
  BatchProperties.class,
  DiscordTimeoutProperties.class,
  MonitoringThresholdProperties.class
})
@RequiredArgsConstructor
public class PropertiesConfig {

  private final BatchProperties batchProperties;
  private final maple.expectation.global.resilience.MySQLFallbackProperties mySQLFallbackProperties;

  /**
   * Inject batch size from BatchProperties to MySQLFallbackProperties for consistency.
   *
   * <p>This ensures all batch sizes are centrally managed in one place (expectation.batch).
   */
  @PostConstruct
  public void configureBatchSizes() {
    mySQLFallbackProperties.setSyncBatchSize(batchProperties.mysqlFallbackSyncSize());
  }
}
