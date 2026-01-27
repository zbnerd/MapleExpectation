package maple.expectation.support;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * Redis Sentinel HA 테스트 베이스 클래스 (7개 컨테이너)
 *
 * <p>CLAUDE.md Section 24 준수:
 * <ul>
 *   <li>Singleton Container Pattern: 전체 테스트 세션에서 컨테이너 단 1회 실행</li>
 *   <li>Awaitility 기반 비동기 대기 (Thread.sleep() 금지)</li>
 *   <li>테스트 간 상태 격리 (globalProxyReset)</li>
 * </ul>
 *
 * <h4>사용 대상</h4>
 * <ul>
 *   <li>Redis Sentinel Failover 테스트</li>
 *   <li>HA(High Availability) 시나리오 검증</li>
 * </ul>
 *
 * <h4>컨테이너 구성 (7개)</h4>
 * <ul>
 *   <li>MySQL 8.0</li>
 *   <li>Redis Master + Slave</li>
 *   <li>Toxiproxy (장애 주입)</li>
 *   <li>Sentinel x 3</li>
 * </ul>
 *
 * <h4>예상 시작 시간: ~30초</h4>
 *
 * @see SimpleRedisContainerBase 경량 테스트용
 * @see AbstractContainerBaseTest Toxiproxy만 필요한 P0 Chaos 테스트용
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "nexon.api.key=dummy-test-key"
})
@Tag("sentinel")
public abstract class SentinelContainerBase {

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
            .withStartupTimeout(Duration.ofMinutes(5));

    // -------------------------------------------------------------------------
    // Redis Master/Slave
    // -------------------------------------------------------------------------
    protected static final GenericContainer<?> REDIS_MASTER = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
            .withCommand("redis-server", "--port", "6379")
            .withExposedPorts(6379)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis-master")
            .waitingFor(Wait.forListeningPort());

    protected static final GenericContainer<?> REDIS_SLAVE = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
            .withCommand("redis-server", "--port", "6379", "--slaveof", "redis-master", "6379")
            .withExposedPorts(6379)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis-slave")
            .waitingFor(Wait.forListeningPort());

    // -------------------------------------------------------------------------
    // Toxiproxy (장애 주입용)
    // -------------------------------------------------------------------------
    protected static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
            .withNetwork(NETWORK)
            .withStartupTimeout(Duration.ofMinutes(5));

    // -------------------------------------------------------------------------
    // Sentinel Nodes x 3
    // -------------------------------------------------------------------------
    protected static final GenericContainer<?> SENTINEL_1 = createSentinelContainer(26379);
    protected static final GenericContainer<?> SENTINEL_2 = createSentinelContainer(26380);
    protected static final GenericContainer<?> SENTINEL_3 = createSentinelContainer(26381);

    protected static ToxiproxyContainer.ContainerProxy redisProxy;

    static {
        // 모든 컨테이너 병렬 시작
        Startables.deepStart(Stream.of(
                MYSQL, REDIS_MASTER, REDIS_SLAVE, TOXIPROXY,
                SENTINEL_1, SENTINEL_2, SENTINEL_3
        )).join();

        redisProxy = TOXIPROXY.getProxy("redis-master", 6379);
    }

    private static GenericContainer<?> createSentinelContainer(int port) {
        String sentinelConf = String.format(
                "port %d\n" +
                        "sentinel monitor mymaster redis-master 6379 2\n" +
                        "sentinel down-after-milliseconds mymaster 1000\n" +
                        "sentinel parallel-syncs mymaster 1\n" +
                        "sentinel failover-timeout mymaster 3000\n" +
                        "sentinel resolve-hostnames yes\n" +
                        "sentinel announce-hostnames yes\n", port);

        return new GenericContainer<>(DockerImageName.parse("redis:7.0"))
                .withCommand("redis-sentinel", "/etc/redis/sentinel.conf")
                .withCopyToContainer(Transferable.of(sentinelConf), "/etc/redis/sentinel.conf")
                .withExposedPorts(port)
                .withNetwork(NETWORK)
                .waitingFor(Wait.forLogMessage(".*Sentinel ID.*", 1));
    }

    @BeforeEach
    void setUpSentinelBase() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    try {
                        redisProxy.toxics().getAll();
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                });
        globalProxyReset();
    }

    @AfterEach
    protected void tearDownSentinelBase() {
        globalProxyReset();
    }

    /**
     * Master Redis 장애 주입
     */
    protected void failMaster() {
        if (redisProxy != null) {
            redisProxy.setConnectionCut(true);
        }
    }

    /**
     * Master Redis 복구
     */
    protected void recoverMaster() {
        globalProxyReset();
    }

    /**
     * Toxiproxy 상태 초기화 (테스트 간 격리)
     */
    protected void globalProxyReset() {
        if (redisProxy != null) {
            try {
                redisProxy.toxics().getAll().forEach(t -> {
                    try {
                        t.remove();
                    } catch (IOException ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
            try {
                redisProxy.setConnectionCut(false);
            } catch (Exception ignored) {
            }
        }
    }

    @DynamicPropertySource
    static void configureSentinelProperties(DynamicPropertyRegistry registry) {
        // MySQL 연결 설정
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQL8Dialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");

        // Redis Sentinel 연결 설정
        String sentinelNodes = String.format("127.0.0.1:%d,127.0.0.1:%d,127.0.0.1:%d",
                SENTINEL_1.getMappedPort(26379),
                SENTINEL_2.getMappedPort(26380),
                SENTINEL_3.getMappedPort(26381));
        registry.add("spring.data.redis.sentinel.master", () -> "mymaster");
        registry.add("spring.data.redis.sentinel.nodes", () -> sentinelNodes);

        // Redisson NAT 매핑 (Docker 네트워크 → 로컬호스트)
        String natMapping = String.format("redis-master:6379=127.0.0.1:%d,redis-slave:6379=127.0.0.1:%d",
                REDIS_MASTER.getMappedPort(6379), REDIS_SLAVE.getMappedPort(6379));
        registry.add("redis.nat-mapping", () -> natMapping);

        // Toxiproxy Host/Port (장애 주입 시 사용)
        registry.add("spring.data.redis.host", TOXIPROXY::getHost);
        registry.add("spring.data.redis.port", () -> redisProxy.getProxyPort());
    }
}
