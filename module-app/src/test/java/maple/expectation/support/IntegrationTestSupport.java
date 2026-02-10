package maple.expectation.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests.
 *
 * <p>Provides common Spring Boot test configuration with active profile set to "test".
 *
 * @see SpringBootTest
 * @see ActiveProfiles
 */
@SpringBootTest(classes = maple.expectation.ExpectationApplication.class)
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {
  // Base class for Spring Boot integration tests
  // All test configuration is inherited via annotations
}
