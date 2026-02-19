package maple.expectation.support;

import maple.expectation.config.ChaosTestConfig;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests requiring Testcontainers.
 *
 * <p>Manages Docker containers for MySQL and Redis lifecycle. Containers are shared across all test
 * classes to reduce startup time.
 *
 * <p>Note: ToxiProxy integration is currently disabled. Chaos tests that require network fault
 * injection will need to be re-enabled with proper ToxiProxy setup.
 */
@Testcontainers
@SpringBootTest(classes = maple.expectation.ExpectationApplication.class)
@ActiveProfiles("chaos")
@Import(ChaosTestConfig.class)
public abstract class AbstractContainerBaseTest {

  /** Shared MySQL container for all tests. Uses testcontainers/MySQL 8.0 image. */
  protected static final MySQLContainer<?> MYSQL_CONTAINER =
      new MySQLContainer<>("mysql:8.0")
          .withDatabaseName("testdb")
          .withUsername("tc_test_user_8xq2")
          .withPassword("K9$mP2vL5xR8nQ3wT7#yC4fG6hJ")
          .withReuse(true);

  /** Shared Redis container for all tests. Uses testcontainers/Redis 7-alpine image. */
  @SuppressWarnings("resource")
  protected static final GenericContainer<?> REDIS_CONTAINER =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379).withReuse(true);

  // Static initializer to start containers before Spring context loads
  static {
    MYSQL_CONTAINER.start();
    REDIS_CONTAINER.start();

    // Set system properties for Spring Boot to use Testcontainers URLs
    System.setProperty("spring.datasource.url", MYSQL_CONTAINER.getJdbcUrl());
    System.setProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
    System.setProperty("spring.datasource.username", MYSQL_CONTAINER.getUsername());
    System.setProperty("spring.datasource.password", MYSQL_CONTAINER.getPassword());
    System.setProperty("spring.data.redis.host", REDIS_CONTAINER.getHost());
    System.setProperty("spring.data.redis.port", REDIS_CONTAINER.getMappedPort(6379).toString());
  }

  /** Stop containers after all tests complete. */
  @AfterAll
  static void stopContainers() {
    // Containers are reused, so we don't stop them here
    // They will be cleaned up by Ryuk or manually
  }

  /**
   * Get JDBC URL of the MySQL container.
   *
   * @return JDBC URL
   */
  protected static String getJdbcUrl() {
    return MYSQL_CONTAINER.getJdbcUrl();
  }

  /**
   * Get Redis host of the Redis container.
   *
   * @return Redis host
   */
  protected static String getRedisHost() {
    return REDIS_CONTAINER.getHost();
  }

  /**
   * Get Redis port of the Redis container.
   *
   * @return Redis port
   */
  protected static Integer getRedisPort() {
    return REDIS_CONTAINER.getMappedPort(6379);
  }
}
