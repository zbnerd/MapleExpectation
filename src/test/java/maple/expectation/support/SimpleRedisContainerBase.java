package maple.expectation.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

import java.io.IOException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.stream.Stream;

/**
 * 경량 통합 테스트 베이스 클래스 (MySQL + Redis만)
 *
 * <p>CLAUDE.md Section 24 준수:
 * <ul>
 *   <li>Singleton Container Pattern: 전체 테스트 세션에서 컨테이너 단 1회 실행</li>
 *   <li>Template Method Pattern: 서브클래스에서 확장 가능한 구조</li>
 *   <li>OCP 준수: 새 컨테이너 구성 필요 시 수정 없이 확장</li>
 * </ul>
 *
 * <h4>사용 대상</h4>
 * <ul>
 *   <li>대부분의 통합 테스트 (90%)</li>
 *   <li>Toxiproxy/Sentinel이 불필요한 테스트</li>
 * </ul>
 *
 * <h4>컨테이너 구성</h4>
 * <ul>
 *   <li>MySQL 8.0</li>
 *   <li>Redis 7.0 (단일 노드)</li>
 * </ul>
 *
 * <h4>예상 시작 시간: ~3초</h4>
 *
 * @see AbstractContainerBaseTest Toxiproxy 필요 시 사용
 * @see SentinelContainerBase Sentinel HA 테스트 시 사용
 */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
public abstract class SimpleRedisContainerBase {

    protected static final Network NETWORK = Network.newNetwork();

    // -------------------------------------------------------------------------
    // MySQL Container
    // -------------------------------------------------------------------------
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("maple_expectation")
            .withUsername("root")
            .withPassword("1234")
            .withNetwork(NETWORK)
            .withNetworkAliases("mysql-db")
            .waitingFor(Wait.forLogMessage(".*ready for connections.*\\s", 2))
            .withStartupTimeout(Duration.ofMinutes(5))
            .withReuse(true);

    // -------------------------------------------------------------------------
    // Redis Container (단일 노드)
    // -------------------------------------------------------------------------
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
            .withExposedPorts(6379)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis-server")
            .waitingFor(Wait.forListeningPort())
            .withReuse(true);

    static {
        // 병렬 시작으로 컨테이너 초기화 시간 단축
        Startables.deepStart(Stream.of(MYSQL, REDIS)).join();
    }

    /**
     * 테스트 간 데이터 격리를 위한 Redis 초기화
     *
     * <p>Singleton Container 패턴에서 테스트 간 데이터 누수를 방지합니다.
     * 서브클래스에서 override하여 추가 정리 로직(DB cleanup 등)을 수행할 수 있습니다.
     */
    @AfterEach
    protected void cleanupTestData() {
        try {
            REDIS.execInContainer("redis-cli", "FLUSHDB");
        } catch (IOException | InterruptedException e) {
            System.err.println("[cleanupTestData] Redis FLUSHDB failed (best-effort): " + e.getMessage());
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // MySQL 연결 설정
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQL8Dialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");

        // Redis 연결 설정 (단일 노드 모드)
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);

        // Sentinel 비활성화 (단일 노드 모드)
        registry.add("spring.data.redis.sentinel.master", () -> "");
        registry.add("spring.data.redis.sentinel.nodes", () -> "");
    }
}
