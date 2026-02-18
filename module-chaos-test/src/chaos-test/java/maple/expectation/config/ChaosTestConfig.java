package maple.expectation.config;

import maple.expectation.common.resource.ResourceLoader;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Chaos Test Configuration providing common beans for chaos/nightmare tests.
 *
 * <p>This configuration is automatically loaded for all chaos tests to provide common
 * infrastructure beans.
 */
@TestConfiguration
public class ChaosTestConfig {

  /**
   * ResourceLoader bean for infrastructure components.
   *
   * <p>Required by components like TwoBucketRateLimiter that need to load resources.
   */
  @Bean
  public ResourceLoader resourceLoader() {
    return new ResourceLoader();
  }
}
