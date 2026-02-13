package maple.expectation.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import maple.expectation.alert.AlertPriority;
import maple.expectation.alert.channel.AlertChannel;
import maple.expectation.alert.channel.DiscordAlertChannel;
import maple.expectation.alert.channel.InMemoryAlertBuffer;
import maple.expectation.alert.channel.LocalFileAlertChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Alert Channel Configuration
 *
 * <p>Defines channel providers mapping for each alert priority level
 *
 * <p>Channel Selection Strategy:
 *
 * <ul>
 *   <li>CRITICAL: DiscordAlertChannel (immediate external notification)
 *   <li>NORMAL: InMemoryAlertBuffer (buffered for batch processing)
 *   <li>BACKGROUND: InMemoryAlertBuffer (buffered for low-priority batch)
 * </ul>
 *
 * @author ADR-0345
 * @since 2025-02-13
 */
@Configuration
@RequiredArgsConstructor
public class AlertChannelConfig {

  private final DiscordAlertChannel discordAlertChannel;
  private final InMemoryAlertBuffer inMemoryAlertBuffer;
  private final AlertFeatureProperties alertFeatureProperties;
  private final maple.expectation.infrastructure.executor.LogicExecutor logicExecutor;

  /**
   * Alert Log File Path Bean
   *
   * <p>Creates Path bean from configured alert file path
   *
   * @return Path to alert log file
   */
  @Bean
  public Path alertLogFilePath() {
    return Path.of(alertFeatureProperties.getFile().getPath());
  }

  /**
   * Local File Alert Channel Bean
   *
   * <p>Creates file-based alert channel as fallback
   *
   * @return LocalFileAlertChannel instance
   */
  @Bean
  public LocalFileAlertChannel localFileAlertChannel(Path alertLogFilePath) {
    return new LocalFileAlertChannel(alertLogFilePath, logicExecutor);
  }

  /**
   * Channel Providers Bean
   *
   * <p>Maps AlertPriority to Supplier<AlertChannel> for lazy channel resolution
   *
   * @return Map of priority to channel provider
   */
  @Bean
  public Map<AlertPriority, Supplier<AlertChannel>> channelProviders() {
    return Map.of(
        AlertPriority.CRITICAL,
        () -> discordAlertChannel,
        AlertPriority.NORMAL,
        () -> inMemoryAlertBuffer,
        AlertPriority.BACKGROUND,
        () -> inMemoryAlertBuffer);
  }
}
