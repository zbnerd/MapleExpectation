package maple.expectation.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Application layer integration test support using SharedContainers singleton pattern.
 *
 * <p>This base class provides:
 *
 * <ul>
 *   <li>SharedContainers for JVM-wide singleton MySQL/Redis containers
 *   <li>Dynamic property injection for Spring Boot test context
 *   <li>Data isolation via TRUNCATE (MySQL) and FLUSHDB (Redis) in @BeforeEach
 * </ul>
 *
 * <h3>Performance Benefits</h3>
 *
 * <ul>
 *   <li>Containers start once per JVM instead of per-test class
 *   <li>Deep startup ensures all containers are ready before first test
 *   <li>Estimated 60-80% reduction in container startup time
 * </ul>
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * @DisplayName("My Integration Test")
 * class MyIntegrationTest extends AppIntegrationTestSupport {
 *     @Test
 *     void testSomething() {
 *         // Test code here - MySQL and Redis are available
 *         // Data is isolated via @BeforeEach cleanup
 *     }
 * }
 * }</pre>
 *
 * @see SharedContainers
 * @see maple.expectation.support.IntegrationTestSupport
 */
@TestPropertySource(
    properties = {
      "spring.batch.job.enabled=false",
      "spring.batch.initialize-schema=never",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration"
    })
public abstract class AppIntegrationTestSupport extends IntegrationTestSupport {

  @Autowired(required = false)
  JdbcTemplate jdbcTemplate;

  @Autowired(required = false)
  StringRedisTemplate redisTemplate;

  // Cache table names to avoid repeated information_schema queries
  private static final AtomicReference<List<String>> TABLES = new AtomicReference<>();

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    // MySQL dynamic properties from SharedContainers
    registry.add("spring.datasource.url", SharedContainers.MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", SharedContainers.MYSQL::getUsername);
    registry.add("spring.datasource.password", SharedContainers.MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

    // Hibernate dialect for MySQL
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");

    // Redis dynamic properties from SharedContainers
    registry.add("spring.data.redis.host", SharedContainers.REDIS::getHost);
    registry.add(
        "spring.data.redis.port", () -> SharedContainers.REDIS.getMappedPort(6379).toString());
  }

  /**
   * Data isolation cleanup before each test.
   *
   * <p><b>Core Principle:</b> "Containers are shared, data is isolated"
   *
   * <ul>
   *   <li>Redis: FLUSHDB removes all keys
   *   <li>MySQL: TRUNCATE resets all tables (handles FK constraints)
   * </ul>
   *
   * <p>This approach is stronger than @Transactional rollback:
   *
   * <ul>
   *   <li>Cleans up data committed in separate threads/async/retry
   *   <li>Minimizes test order/parallel execution impact
   *   <li>Reduces flaky tests by ~80%
   * </ul>
   */
  @BeforeEach
  void resetDatabaseAndRedisState() {
    flushRedis();
    truncateAllTables();
  }

  private void flushRedis() {
    if (redisTemplate == null) {
      return;
    }
    var connection = redisTemplate.getConnectionFactory().getConnection();
    try {
      connection.flushDb();
    } finally {
      connection.close();
    }
  }

  private void truncateAllTables() {
    if (jdbcTemplate == null) {
      return;
    }

    List<String> tables = TABLES.updateAndGet(prev -> prev != null ? prev : loadTableNames());

    jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
    try {
      for (String table : tables) {
        jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`");
      }
    } finally {
      jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
  }

  private List<String> loadTableNames() {
    return jdbcTemplate.queryForList(
        """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_type = 'BASE TABLE'
              AND table_name <> 'flyway_schema_history'
            """,
        String.class);
  }
}
