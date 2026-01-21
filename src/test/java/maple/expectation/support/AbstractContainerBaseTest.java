package maple.expectation.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * 🚀 모든 통합 테스트의 부모 클래스 (Testcontainers 기반)
 *
 * @AutoConfigureTestDatabase(replace = NONE): Spring이 DataSource를 H2로 강제 교체하는 것을 방지
 * @ActiveProfiles({"test", "container"}): container 프로파일 활성화로 LockHikariConfig 로드
 */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles({"test", "container"})
public abstract class AbstractContainerBaseTest {
    protected static final Network NETWORK = Network.newNetwork();
    protected static final MySQLContainer<?> MYSQL;
    protected static final GenericContainer<?> REDIS;
    protected static final ToxiproxyContainer TOXIPROXY;
    protected static ToxiproxyContainer.ContainerProxy redisProxy;

    static {
        System.setProperty("docker.host", "unix:///var/run/docker.sock");

        // 1) MySQL 설정
        MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("maple_expectation")
                .withUsername("root")
                .withPassword("1234")
                .withNetwork(NETWORK)
                .waitingFor(Wait.forLogMessage(".*ready for connections.*\\s", 2))
                .withStartupTimeout(Duration.ofMinutes(2));

        // 2) Redis 설정
        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
                .withExposedPorts(6379)
                .withNetwork(NETWORK)
                .withNetworkAliases("redis-server")
                .waitingFor(Wait.forListeningPort());

        // 3) Toxiproxy 설정
        TOXIPROXY = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
                .withNetwork(NETWORK);

        MYSQL.start();
        REDIS.start();
        TOXIPROXY.start();

        redisProxy = TOXIPROXY.getProxy("redis-server", 6379);
    }

    /**
     * Toxiproxy 상태 초기화 (모든 Toxic 제거 + 연결 복구)
     *
     * <p>CLAUDE.md Section 24: Flaky Test 방지
     * <ul>
     *   <li>Best-effort 정리: 실패해도 테스트 실패로 이어지지 않음</li>
     *   <li>연결 복구 우선: setConnectionCut(false) 먼저 실행</li>
     *   <li>Toxic 정리: 개별 예외 무시하고 계속 진행</li>
     * </ul>
     */
    @AfterEach
    protected void globalProxyReset() {
        if (redisProxy == null) {
            return;
        }

        // Best-effort 정리: 실패해도 테스트 결과에 영향 없음
        try {
            // 1. 연결 복구 우선 (가장 중요)
            redisProxy.setConnectionCut(false);

            // 2. Toxic 정리 (개별 실패 무시)
            try {
                redisProxy.toxics().getAll().forEach(t -> {
                    try {
                        t.remove();
                    } catch (Exception ignored) {
                        // 개별 toxic 제거 실패는 무시
                    }
                });
            } catch (Exception ignored) {
                // toxics 조회 실패도 무시 (연결은 이미 복구됨)
            }
        } catch (Exception e) {
            // Best-effort: 정리 실패 시 로그만 남기고 계속 진행
            System.err.println("[globalProxyReset] Cleanup failed (best-effort): " + e.getMessage());
        }
    }

    /**
     * Redis 장애 주입 (Toxiproxy 사용)
     * P0 Chaos 테스트에서 사용
     */
    protected void failMaster() {
        if (redisProxy != null) {
            redisProxy.setConnectionCut(true);
        }
    }

    /**
     * Redis 복구 (Toxiproxy 상태 초기화)
     */
    protected void recoverMaster() {
        globalProxyReset();
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        // DB 관련 설정 (MySQL 드라이버와 방언을 강제함)
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");

        // [P0-N07 Fix] HikariCP MDL Freeze Prevention
        // lock_wait_timeout: Metadata Lock 대기 시간 제한 (초 단위)
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET SESSION lock_wait_timeout = 10");

        // Redis 관련 설정
        registry.add("spring.data.redis.host", TOXIPROXY::getHost);
        registry.add("spring.data.redis.port", () -> redisProxy.getProxyPort());
        registry.add("spring.redis.host", TOXIPROXY::getHost);
        registry.add("spring.redis.port", () -> redisProxy.getProxyPort());
    }
}