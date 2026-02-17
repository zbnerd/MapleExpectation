package maple.expectation.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for Redis Sentinel tests requiring Testcontainers.
 *
 * <p>Manages Docker containers for Redis master, slaves, and sentinels lifecycle. Containers are
 * shared across all test classes to reduce startup time.
 */
@Testcontainers
@SpringBootTest(classes = maple.expectation.ExpectationApplication.class)
@ActiveProfiles("chaos")
public abstract class SentinelContainerBase {

  /** Shared Redis master container for all tests. */
  @SuppressWarnings("resource")
  protected static final GenericContainer<?> REDIS_MASTER =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--save", "")
          .withReuse(true);

  /** Shared Redis slave container for all tests. */
  @SuppressWarnings("resource")
  protected static final GenericContainer<?> REDIS_SLAVE =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--save", "", "--slaveof", "redis-master", "6379")
          .withReuse(true);

  /** Shared Redis sentinel container for all tests. */
  @SuppressWarnings("resource")
  protected static final GenericContainer<?> REDIS_SENTINEL =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(26379)
          .withCommand("redis-sentinel", "/etc/redis/sentinel.conf")
          .withReuse(true);

  /** Start containers before any tests run. */
  @BeforeAll
  static void startContainers() {
    REDIS_MASTER.start();
    REDIS_SLAVE.start();
    REDIS_SENTINEL.start();

    // Configure network for slave-of command
    String masterHost = REDIS_MASTER.getHost();
    int masterPort = REDIS_MASTER.getMappedPort(6379);

    // Set system properties for Spring Boot to use Testcontainers URLs
    System.setProperty("spring.data.redis.sentinel.master", "mymaster");
    System.setProperty(
        "spring.data.redis.sentinel.nodes",
        REDIS_SENTINEL.getHost() + ":" + REDIS_SENTINEL.getMappedPort(26379));
  }

  /** Stop containers after all tests complete. */
  @AfterAll
  static void stopContainers() {
    REDIS_SENTINEL.stop();
    REDIS_SLAVE.stop();
    REDIS_MASTER.stop();
  }

  /**
   * Get Redis master host.
   *
   * @return Redis master host
   */
  protected static String getMasterHost() {
    return REDIS_MASTER.getHost();
  }

  /**
   * Get Redis master port.
   *
   * @return Redis master port
   */
  protected static Integer getMasterPort() {
    return REDIS_MASTER.getMappedPort(6379);
  }

  /**
   * Get Redis sentinel host.
   *
   * @return Redis sentinel host
   */
  protected static String getSentinelHost() {
    return REDIS_SENTINEL.getHost();
  }

  /**
   * Get Redis sentinel port.
   *
   * @return Redis sentinel port
   */
  protected static Integer getSentinelPort() {
    return REDIS_SENTINEL.getMappedPort(26379);
  }
}
