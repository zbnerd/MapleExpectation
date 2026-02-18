package maple.expectation.support;

import maple.expectation.config.ChaosTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests.
 *
 * <p>Provides common Spring Boot test configuration with active profile set to "chaos".
 *
 * <p>Automatically performs comprehensive environment cleanup before each test method:
 *
 * <ul>
 *   <li>Database: TRUNCATE all tables (fast, deterministic)
 *   <li>L1 Cache (Caffeine): Clear local cache
 *   <li>L2 Cache (Redis): Flush database
 *   <li>Circuit Breakers: Reset all Resilience4j state
 * </ul>
 *
 * <h3>NO @DirtiesContext:</h3>
 *
 * <p>This approach eliminates the need for {@code @DirtiesContext}, which would recreate the entire
 * Spring Context and add 5-10 seconds per test. With Testcontainers reuse enabled, tests run
 * significantly faster.
 *
 * @see SpringBootTest
 * @see ActiveProfiles
 * @see ChaosTestCleaner
 */
@SpringBootTest(classes = maple.expectation.ExpectationApplication.class)
@ActiveProfiles("chaos")
@Import(ChaosTestConfig.class)
public abstract class IntegrationTestSupport {

  @Autowired(required = false)
  private ChaosTestCleaner chaosTestCleaner;

  @BeforeEach
  void setUpEnvironment() {
    if (chaosTestCleaner != null) {
      chaosTestCleaner.cleanAll();
    }
  }
}
