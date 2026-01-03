package maple.expectation.support;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;

/**
 * Redis Sentinel Failover 테스트를 위한 Testcontainers 기반 베이스 클래스
 *
 * <p>구성:
 * <ul>
 *   <li>Redis Master 1대</li>
 *   <li>Redis Slave 1대</li>
 *   <li>Sentinel 3대 (quorum 2)</li>
 *   <li>Toxiproxy (Master 장애 주입용)</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@ContextConfiguration(initializers = AbstractSentinelContainerBaseTest.Initializer.class)
public abstract class AbstractSentinelContainerBaseTest {

    protected static final Network NETWORK = Network.newNetwork();

    // Redis Master
    @Container
    protected static final GenericContainer<?> MASTER_REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.0"))
            .withCommand("redis-server", "--port", "6379")
            .withExposedPorts(6379)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis-master")
            .waitingFor(Wait.forListeningPort());

    // Redis Slave
    @Container
    protected static final GenericContainer<?> SLAVE_REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.0"))
            .withCommand("redis-server", "--port", "6379", "--slaveof", "redis-master", "6379")
            .withExposedPorts(6379)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis-slave")
            .waitingFor(Wait.forListeningPort());

    // Toxiproxy (Master 장애 주입용)
    @Container
    protected static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
            .withNetwork(NETWORK);

    protected static ToxiproxyContainer.ContainerProxy masterProxy;

    // Sentinel 1 (Master/Slave 시작 후 실행)
    @Container
    protected static final GenericContainer<?> SENTINEL_1 = createSentinel(26379)
            .dependsOn(MASTER_REDIS, SLAVE_REDIS);

    // Sentinel 2 (Master/Slave 시작 후 실행)
    @Container
    protected static final GenericContainer<?> SENTINEL_2 = createSentinel(26380)
            .dependsOn(MASTER_REDIS, SLAVE_REDIS);

    // Sentinel 3 (Master/Slave 시작 후 실행)
    @Container
    protected static final GenericContainer<?> SENTINEL_3 = createSentinel(26381)
            .dependsOn(MASTER_REDIS, SLAVE_REDIS);

    static {
        TOXIPROXY.start();
        masterProxy = TOXIPROXY.getProxy("redis-master", 6379);
    }

    /**
     * Sentinel 컨테이너 생성 (quorum 2 설정)
     */
    private static GenericContainer<?> createSentinel(int port) {
        String sentinelConf = String.format(
                "port %d\n" +
                "sentinel monitor mymaster redis-master 6379 2\n" +
                "sentinel down-after-milliseconds mymaster 1000\n" +
                "sentinel parallel-syncs mymaster 1\n" +
                "sentinel failover-timeout mymaster 3000\n" +
                "sentinel resolve-hostnames yes\n" +
                "sentinel announce-hostnames yes\n" +
                "logfile \"\"\n",
                port
        );

        return new GenericContainer<>(DockerImageName.parse("redis:7.0"))
                .withCommand("redis-sentinel", "/etc/redis/sentinel.conf")
                .withCopyToContainer(Transferable.of(sentinelConf), "/etc/redis/sentinel.conf")
                .withExposedPorts(port)
                .withNetwork(NETWORK)
                .waitingFor(Wait.forLogMessage(".*Sentinel ID.*", 1))
                .withStartupTimeout(java.time.Duration.ofMinutes(2));
    }

    /**
     * Spring Boot 프로퍼티에 Sentinel 노드 정보 주입
     */
    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            String sentinelNodes = String.format(
                    "%s:%d,%s:%d,%s:%d",
                    SENTINEL_1.getHost(), SENTINEL_1.getMappedPort(26379),
                    SENTINEL_2.getHost(), SENTINEL_2.getMappedPort(26380),
                    SENTINEL_3.getHost(), SENTINEL_3.getMappedPort(26381)
            );

            // NAT 매핑 정보: Docker 네트워크 내부 주소 → 외부 매핑 주소
            String natMapping = String.format(
                    "redis-master:6379=%s:%d,redis-slave:6379=%s:%d",
                    MASTER_REDIS.getHost(), MASTER_REDIS.getMappedPort(6379),
                    SLAVE_REDIS.getHost(), SLAVE_REDIS.getMappedPort(6379)
            );

            TestPropertyValues.of(
                    "spring.data.redis.sentinel.master=mymaster",
                    "spring.data.redis.sentinel.nodes=" + sentinelNodes,
                    // Redisson NAT 매핑 정보 제공
                    "redis.nat-mapping=" + natMapping
            ).applyTo(context.getEnvironment());
        }
    }

    /**
     * 각 테스트 전에 Toxiproxy 리셋 (장애 주입 제거)
     */
    @BeforeEach
    void setUp() throws IOException {
        globalProxyReset();
    }

    /**
     * Toxiproxy 리셋: 모든 toxic 제거 및 연결 복구
     */
    protected void globalProxyReset() throws IOException {
        if (masterProxy != null) {
            try {
                masterProxy.toxics().getAll().forEach(toxic -> {
                    try {
                        toxic.remove();
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
            masterProxy.setConnectionCut(false);
        }
    }

    /**
     * Master 장애 주입: 네트워크 연결 차단
     */
    protected void failMaster() throws IOException {
        masterProxy.setConnectionCut(true);
    }

    /**
     * Master 복구: 네트워크 연결 복구
     */
    protected void recoverMaster() throws IOException {
        masterProxy.setConnectionCut(false);
    }

    /**
     * Master에 지연 추가 (Latency Toxic)
     */
    protected void addLatencyToMaster(long latencyMs) throws IOException {
        masterProxy.toxics()
                .latency("latency", ToxicDirection.UPSTREAM, latencyMs);
    }
}
