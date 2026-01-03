package maple.expectation.support;

import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.repository.v2.RedisBufferRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.awaitility.Awaitility;

import java.io.IOException;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * 🚀 [Issue #133] 통합 테스트 최적화 부모 클래스
 * - Singleton Container: 전체 테스트 세션에서 컨테이너 단 1회 실행
 * - Context Caching: 공통 Mock Bean 선언으로 ApplicationContext 재생성 방지
 * - Fault Injection: failMaster, recoverMaster 등 장애 유도 메서드 제공
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "nexon.api.key=dummy-test-key" // API 키 에러 방지
})
public abstract class IntegrationTestSupport {

    // -------------------------------------------------------------------------
    // 1. [Mock 구역] 외부 연동 및 알림만 Mock으로 유지 (캐싱용)
    // -------------------------------------------------------------------------
    @MockitoBean protected RealNexonApiClient nexonApiClient;
    @MockitoBean protected DiscordAlertService discordAlertService;
    // 💡 MonitoringAlertService는 실제 Bean을 테스트해야 하는 경우가 있으므로 Mock에서 제거
    // 필요한 테스트에서 개별적으로 Mock으로 선언할 수 있음

    // -------------------------------------------------------------------------
    // 2. [Real Bean 구역] 실제 DB에 저장되어야 하므로 @MockitoBean에서 삭제하고 @Autowired로 변경
    // -------------------------------------------------------------------------
    @Autowired protected GameCharacterFacade gameCharacterFacade;
    @Autowired protected GameCharacterRepository gameCharacterRepository;
    @Autowired protected CharacterEquipmentRepository equipmentRepository;
    @Autowired protected LockStrategy lockStrategy; // 실제 Redis 락 작동 확인용
    @Autowired protected RedisBufferRepository redisBufferRepository; // 실제 Redis 버퍼 확인용

    protected static final Network NETWORK = Network.newNetwork();

    // 1. MySQL Container
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("maple_expectation")
            .withUsername("root")
            .withPassword("1234")
            .withNetwork(NETWORK)
            .withNetworkAliases("mysql-db")
            .waitingFor(Wait.forLogMessage(".*ready for connections.*\\s", 2));

    // 2. Redis Master/Slave
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

    // 3. Toxiproxy
    protected static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
            .withNetwork(NETWORK);

    // 4. Sentinel Nodes
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
    void setUpBase() {
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
    protected void tearDownBase() {
        globalProxyReset();
    }

    protected void failMaster() {
        if (redisProxy != null) redisProxy.setConnectionCut(true);
    }

    protected void recoverMaster() {
        globalProxyReset();
    }

    protected void globalProxyReset() {
        if (redisProxy != null) {
            try {
                redisProxy.toxics().getAll().forEach(t -> {
                    try { t.remove(); } catch (IOException ignored) {}
                });
            } catch (Exception ignored) {}
            try {
                redisProxy.setConnectionCut(false);
            } catch (Exception ignored) {}
        }
    }

    @DynamicPropertySource
    static void updateProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQL8Dialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");

        // MySQL 연결 정보
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        // Redis & Sentinel 정보
        String sentinelNodes = String.format("127.0.0.1:%d,127.0.0.1:%d,127.0.0.1:%d",
                SENTINEL_1.getMappedPort(26379),
                SENTINEL_2.getMappedPort(26380),
                SENTINEL_3.getMappedPort(26381));
        registry.add("spring.data.redis.sentinel.master", () -> "mymaster");
        registry.add("spring.data.redis.sentinel.nodes", () -> sentinelNodes);

        // Redisson NAT 매핑
        String natMapping = String.format("redis-master:6379=127.0.0.1:%d,redis-slave:6379=127.0.0.1:%d",
                REDIS_MASTER.getMappedPort(6379), REDIS_SLAVE.getMappedPort(6379));
        registry.add("redis.nat-mapping", () -> natMapping);

        // Toxiproxy Host/Port
        registry.add("spring.data.redis.host", TOXIPROXY::getHost);
        registry.add("spring.data.redis.port", () -> redisProxy.getProxyPort());
    }
}