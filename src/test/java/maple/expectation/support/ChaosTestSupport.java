package maple.expectation.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for chaos engineering tests.
 *
 * <p>Provides common Spring Boot test configuration with active profile set to "test". Chaos tests
 * validate system resilience under failure conditions.
 *
 * @see SpringBootTest
 * @see ActiveProfiles
 */
@SpringBootTest(classes = maple.expectation.ExpectationApplication.class)
@ActiveProfiles("test")
public abstract class ChaosTestSupport {
  // Base class for chaos engineering tests
  // All test configuration is inherited via annotations
}
