package maple.expectation.support;

import maple.expectation.config.ChaosTestConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests.
 *
 * <p>Provides common Spring Boot test configuration with active profile set to "chaos".
 *
 * @see SpringBootTest
 * @see ActiveProfiles
 */
@SpringBootTest(classes = maple.expectation.ExpectationApplication.class)
@ActiveProfiles("chaos")
@Import(ChaosTestConfig.class)
public abstract class IntegrationTestSupport {
  // Base class for Spring Boot integration tests
  // All test configuration is inherited via annotations
}
