package maple.expectation.alert.channel;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Alert Test Configuration
 *
 * <p>Provides test-specific configuration for alert channel tests
 *
 * <p>This configuration creates only the alertWebClient bean to avoid conflicts with the main
 * webclient bean
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@TestConfiguration
public class AlertTestConfig {

  /**
   * Test-specific alert WebClient bean
   *
   * <p>Uses @Primary to ensure this bean is used instead of the main webclient bean
   */
  @Bean
  @Primary
  public WebClient testAlertWebClient() {
    return org.springframework.web.reactive.function.client.WebClient.builder().build();
  }
}
