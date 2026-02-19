package maple.expectation.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton container manager for Testcontainers.
 *
 * <p>Uses a holder class idiom to ensure containers are started only once across all test classes,
 * even with different ClassLoaders.
 *
 * <p>This works because the JVM guarantees that static initializers run exactly once when a class
 * is first loaded.
 */
public final class ContainerManager {

  private static final ContainerHolder HOLDER = new ContainerHolder();

  private ContainerManager() {}

  public static MySQLContainer<?> getMySQLContainer() {
    return HOLDER.mysql;
  }

  public static GenericContainer<?> getRedisContainer() {
    return HOLDER.redis;
  }

  public static String getMySQLJdbcUrl() {
    return HOLDER.mysql.getJdbcUrl();
  }

  public static String getMySQLUsername() {
    return HOLDER.mysql.getUsername();
  }

  public static String getMySQLPassword() {
    return HOLDER.mysql.getPassword();
  }

  public static String getRedisHost() {
    return HOLDER.redis.getHost();
  }

  public static Integer getRedisPort() {
    return HOLDER.redis.getMappedPort(6379);
  }

  public static boolean isRunning() {
    return HOLDER.mysql.isRunning() && HOLDER.redis.isRunning();
  }

  /** Holder class for lazy initialization - containers start only when first accessed */
  private static final class ContainerHolder {
    private final MySQLContainer<?> mysql;
    private final GenericContainer<?> redis;

    ContainerHolder() {
      this.mysql =
          new MySQLContainer<>("mysql:8.0")
              .withDatabaseName("testdb")
              .withUsername("tc_test_user")
              .withPassword("tc_test_password")
              .withReuse(true);

      this.redis =
          new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
              .withExposedPorts(6379)
              .withReuse(true);

      // Start containers
      this.mysql.start();
      this.redis.start();

      System.getLogger("ContainerManager")
          .log(
              System.Logger.Level.INFO,
              "Containers started - MySQL: {0}, Redis: {1}",
              mysql.getJdbcUrl(),
              redis.getHost() + ":" + redis.getMappedPort(6379));
    }
  }
}
