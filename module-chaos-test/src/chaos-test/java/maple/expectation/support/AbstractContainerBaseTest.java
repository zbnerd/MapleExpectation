package maple.expectation.support;

import maple.expectation.config.ChaosTestConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests requiring Testcontainers.
 *
 * <p>Uses ContainerManager singleton for shared containers across all test classes.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Single MySQL container shared across ALL tests (via ContainerManager singleton)
 *   <li>Single Redis container shared across ALL tests
 *   <li>Data cleanup between tests (TRUNCATE for MySQL, FLUSHDB for Redis)
 *   <li>Fast test execution (~100ms per test vs ~30s per test with individual containers)
 * </ul>
 */
@Testcontainers
@SpringBootTest(classes = maple.expectation.ExpectationApplication.class)
@ActiveProfiles("chaos")
@Import(ChaosTestConfig.class)
public abstract class AbstractContainerBaseTest {

  @Autowired protected DataSource dataSource;

  @Autowired protected RedisTemplate<String, String> redisTemplate;

  /**
   * Register dynamic properties for Spring Boot using ContainerManager singleton. This ensures
   * containers are started before Spring context loads.
   */
  @DynamicPropertySource
  static void registerContainerProperties(DynamicPropertyRegistry registry) {
    // Access ContainerManager to ensure containers are started
    registry.add("spring.datasource.url", ContainerManager::getMySQLJdbcUrl);
    registry.add("spring.datasource.username", ContainerManager::getMySQLUsername);
    registry.add("spring.datasource.password", ContainerManager::getMySQLPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    registry.add("spring.data.redis.host", ContainerManager::getRedisHost);
    registry.add("spring.data.redis.port", () -> ContainerManager.getRedisPort().toString());
  }

  /** Clean up test data before each test. This ensures test isolation while reusing containers. */
  @BeforeEach
  void cleanupTestData() {
    cleanupMySQL();
    cleanupRedis();
  }

  /** Clean up MySQL data between tests. Uses TRUNCATE for fast cleanup (faster than DELETE). */
  private void cleanupMySQL() {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {

      // Disable foreign key checks for truncate
      stmt.execute("SET FOREIGN_KEY_CHECKS = 0");

      // Get all tables and truncate them
      var rs =
          stmt.executeQuery(
              "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'testdb'");
      StringBuilder truncateSql = new StringBuilder();
      while (rs.next()) {
        String tableName = rs.getString(1);
        truncateSql.append("TRUNCATE TABLE ").append(tableName).append(";");
      }

      if (truncateSql.length() > 0) {
        stmt.execute(truncateSql.toString());
      }

      // Re-enable foreign key checks
      stmt.execute("SET FOREIGN_KEY_CHECKS = 1");

    } catch (Exception e) {
      // Log but don't fail - some tests may not have tables yet
      System.getLogger("ContainerCleanup")
          .log(System.Logger.Level.DEBUG, "MySQL cleanup: " + e.getMessage());
    }
  }

  /** Clean up Redis data between tests. Uses FLUSHDB to clear all keys. */
  private void cleanupRedis() {
    try {
      redisTemplate.getConnectionFactory().getConnection().flushDb();
    } catch (Exception e) {
      System.getLogger("ContainerCleanup")
          .log(System.Logger.Level.DEBUG, "Redis cleanup: " + e.getMessage());
    }
  }
}
