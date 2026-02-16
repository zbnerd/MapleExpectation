package maple.expectation.alert.channel;

import org.springframework.boot.test.context.TestConfiguration;

/**
 * Alert Test Configuration
 *
 * <p>Empty test configuration - alert WebClient is mocked via @MockBean in individual test classes
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@TestConfiguration
public class AlertTestConfig {
  // WebClient mocked via @MockBean(name = "alertWebClient") in test classes
}
