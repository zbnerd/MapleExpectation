package maple.expectation.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 인프라 계층 통합 테스트용 베이스 클래스
 *
 * <p>{@link SharedContainers}에서 관리하는 공유 컨테이너의 연결 정보를 Spring Boot 테스트 컨텍스트의 동적 프로퍼티로 주입합니다.
 *
 * <p><b>데이터 격리 전략 (Flaky Test 방지):</b>
 *
 * <ul>
 *   <li>컨테이너 수명: JVM 동안 1회 (Singleton)
 *   <li>데이터 수명: 테스트마다 TRUNCATE + FLUSHDB로 격리
 * </ul>
 *
 * <p>사용법:
 *
 * <pre>{@code
 * @DataJpaTest
 * @ActiveProfiles("integrationTest")
 * class MyRepositoryTest extends InfraIntegrationTestSupport {
 *     @Test
 *     void test() { }
 * }
 * }</pre>
 */
public abstract class InfraIntegrationTestSupport {

  @Autowired(required = false)
  JdbcTemplate jdbcTemplate;

  @Autowired(required = false)
  StringRedisTemplate redisTemplate;

  // 테이블 목록 캐싱 (매번 information_schema 조회 방지)
  private static final AtomicReference<List<String>> TABLES = new AtomicReference<>();

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    // MySQL 동적 프로퍼티
    registry.add("spring.datasource.url", SharedContainers.MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", SharedContainers.MYSQL::getUsername);
    registry.add("spring.datasource.password", SharedContainers.MYSQL::getPassword);

    // Redis 동적 프로퍼티
    registry.add("spring.data.redis.host", SharedContainers.REDIS::getHost);
    registry.add(
        "spring.data.redis.port", () -> SharedContainers.REDIS.getMappedPort(6379).toString());
  }

  /**
   * 각 테스트 시작 전 데이터 상태를 초기화합니다.
   *
   * <p><b>핵심 원칙:</b> "컨테이너는 공유하지만 데이터는 격리"
   *
   * <ul>
   *   <li>Redis: FLUSHDB로 모든 키 삭제
   *   <li>MySQL: TRUNCATE로 모든 테이블 초기화 (FK 제약처리 포함)
   * </ul>
   *
   * <p>이 방식은 @Transactional 롤백보다 강력합니다:
   *
   * <ul>
   *   <li>별도 스레드/비동기/리트라이에서 커밋된 데이터도 정리
   *   <li>테스트 순서/병렬 영향 최소화
   *   <li>Flaky Test 80% 감소 효과
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
