package maple.expectation.config;

import maple.expectation.common.resource.ResourceLoader;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Global test configuration providing common beans for all tests.
 *
 * <p>This configuration is automatically loaded for all tests to provide common infrastructure
 * beans that may be missing in test context.
 */
@TestConfiguration
public class GlobalTestConfig {

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
