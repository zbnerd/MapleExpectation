package maple.expectation.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.util.stream.Stream;

/**
 * 💡 통합 테스트 최적화 부모 클래스
 * 1. Singleton Container: 모든 통합 테스트에서 컨테이너를 공유함
 * 2. Parallel Startup: 모든 인프라를 동시에 띄워 부팅 시간 단축
 * 3. Context Caching: 동일한 설정을 공유하여 Spring Context 재생성을 방지
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    protected static final Network NETWORK = Network.newNetwork();

    // 1. MySQL Container
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("maple_expectation")
            .withUsername("root")
            .withPassword("1234")
            .withNetwork(NETWORK)
            .withNetworkAliases("mysql-db")
            .waitingFor(Wait.forLogMessage(".*ready for connections.*\\s", 2));

    // 2. Redis Master/Slave (Sentinel 테스트용 포함)
    protected static final GenericContainer<?> REDIS_MASTER = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
            .withCommand("redis-server", "--port", "6379")
            .withNetwork(NETWORK)
            .withNetworkAliases("redis-master")
            .waitingFor(Wait.forListeningPort());

    protected static final GenericContainer<?> REDIS_SLAVE = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
            .withCommand("redis-server", "--port", "6379", "--slaveof", "redis-master", "6379")
            .withNetwork(NETWORK)
            .withNetworkAliases("redis-slave")
            .waitingFor(Wait.forListeningPort());

    // 3. Toxiproxy (장애 주입용)
    protected static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
            .withNetwork(NETWORK);

    // 4. Sentinel Nodes (최소 3개)
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
                "sentinel resolve-hostnames yes\n", port);

        return new GenericContainer<>(DockerImageName.parse("redis:7.0"))
                .withCommand("redis-sentinel", "/etc/redis/sentinel.conf")
                .withCopyToContainer(Transferable.of(sentinelConf), "/etc/redis/sentinel.conf")
                .withNetwork(NETWORK)
                .waitingFor(Wait.forLogMessage(".*Sentinel ID.*", 1));
    }

    @DynamicPropertySource
    static void updateProps(DynamicPropertyRegistry registry) {
        // MySQL Properties
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        // Redis & Sentinel Properties
        String sentinelNodes = String.format("%s:%d,%s:%d,%s:%d",
                SENTINEL_1.getHost(), SENTINEL_1.getMappedPort(26379),
                SENTINEL_2.getHost(), SENTINEL_2.getMappedPort(26380),
                SENTINEL_3.getHost(), SENTINEL_3.getMappedPort(26381));
        
        registry.add("spring.data.redis.sentinel.master", () -> "mymaster");
        registry.add("spring.data.redis.sentinel.nodes", () -> sentinelNodes);

        // Toxiproxy Port for Redis
        registry.add("spring.data.redis.host", TOXIPROXY::getHost);
        registry.add("spring.data.redis.port", () -> redisProxy.getProxyPort());
    }
}