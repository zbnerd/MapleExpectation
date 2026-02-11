package maple.expectation.support;

import java.util.stream.Stream;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers Singleton 패턴 - 통합 테스트용 공유 컨테이너
 *
 * <p>JVM 라이프사이클 동안 단 한 번만 컨테이너를 시작하여 테스트 실행 속도를 최적화합니다. static initializer 블록에서 깊은 시작(Deep Start)을
 * 수행하여 모든 컨테이너가 준비될 때까지 대기합니다.
 *
 * @see <a
 *     href="https://testcontainers.com/guides/testcontainers-container-lifecycle/">Testcontainers
 *     Lifecycle</a>
 */
public final class SharedContainers {

  /** MySQL 컨테이너 - 통합 테스트용 데이터베이스 */
  public static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  /** Redis 컨테이너 - 통합 테스트용 캐시 */
  public static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static {
    // 모든 컨테이너가 완전히 시작될 때까지 대기
    Startables.deepStart(Stream.of(MYSQL, REDIS)).join();
  }

  private SharedContainers() {
    throw new UnsupportedOperationException("Utility class - cannot be instantiated");
  }
}
