package maple.expectation.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.GenericContainer;
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
 * [V5 최적화] WSL2 및 Redisson NAT Mapper 대응 버전
 */
@SpringBootTest
@ContextConfiguration(initializers = AbstractSentinelContainerBaseTest.Initializer.class)
public abstract class AbstractSentinelContainerBaseTest {

    protected static final Network NETWORK = Network.newNetwork();

    protected static final GenericContainer<?> MASTER_REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
            .withCommand("redis-server", "--port", "6379")
            .withExposedPorts(6379)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis-master")
            .waitingFor(Wait.forListeningPort());

    protected static final GenericContainer<?> SLAVE_REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
            .withCommand("redis-server", "--port", "6379", "--slaveof", "redis-master", "6379")
            .withExposedPorts(6379)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis-slave")
            .waitingFor(Wait.forListeningPort());

    protected static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
            .withNetwork(NETWORK)
            .withStartupTimeout(Duration.ofMinutes(2)) // 충분한 시작 시간 확보
            .waitingFor(Wait.forHttp("/version").forPort(8474));

    protected static final GenericContainer<?> SENTINEL_1 = createSentinelContainer(26379);
    protected static final GenericContainer<?> SENTINEL_2 = createSentinelContainer(26380);
    protected static final GenericContainer<?> SENTINEL_3 = createSentinelContainer(26381);

    protected static ToxiproxyContainer.ContainerProxy masterProxy;

    static {
        Startables.deepStart(Stream.of(MASTER_REDIS, SLAVE_REDIS, TOXIPROXY, SENTINEL_1, SENTINEL_2, SENTINEL_3)).join();
        masterProxy = TOXIPROXY.getProxy("redis-master", 6379);
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

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            // 💡 Redisson이 redis-master(내부이름)를 localhost:port(외부포트)로 매핑하게 함
            String sentinelNodes = String.format("127.0.0.1:%d,127.0.0.1:%d,127.0.0.1:%d",
                    SENTINEL_1.getMappedPort(26379),
                    SENTINEL_2.getMappedPort(26380),
                    SENTINEL_3.getMappedPort(26381)
            );

            // 🚀 핵심: Redisson NAT Mapper에서 사용할 매핑 문자열
            String natMapping = String.format("redis-master:6379=127.0.0.1:%d,redis-slave:6379=127.0.0.1:%d",
                    MASTER_REDIS.getMappedPort(6379),
                    SLAVE_REDIS.getMappedPort(6379)
            );

            TestPropertyValues.of(
                    "spring.data.redis.sentinel.master=mymaster",
                    "spring.data.redis.sentinel.nodes=" + sentinelNodes,
                    "redis.nat-mapping=" + natMapping // Redisson 설정 클래스에서 참조
            ).applyTo(context.getEnvironment());
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        // 💡 1. 프록시 자체가 Toxiproxy 서버에 등록될 때까지 대기
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    try {
                        // 프록시 정보 조회가 성공하면 서버에 등록된 것임
                        TOXIPROXY.getProxy("redis-master", 6379);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                });

        globalProxyReset();
    }

    protected void globalProxyReset() {
        if (masterProxy != null) {
            // 💡 2. 모든 Toxic을 리스트로 받아와서 하나씩 제거 (가장 확실한 초기화)
            try {
                masterProxy.toxics().getAll().forEach(toxic -> {
                    try {
                        toxic.remove();
                    } catch (IOException e) {
                        // 이미 삭제되었거나 없는 경우 무시
                    }
                });
            } catch (IOException e) {
                // 프록시를 찾을 수 없는 경우 등 예외 처리
            }

            // 💡 3. setConnectionCut(false) 대신 프록시 자체의 연결 상태를 보장
            // 내부적으로 404가 발생할 수 있는 구조이므로 한 번 더 try-catch로 감쌉니다.
            try {
                masterProxy.setConnectionCut(false);
            } catch (Exception e) {
                // 이미 복구되었거나 Toxic이 없는 경우 발생하는 404 등 무시
            }
        }
    }

    protected void failMaster() throws IOException {
        masterProxy.setConnectionCut(true);
    }

    protected void recoverMaster() throws IOException {
        masterProxy.setConnectionCut(false);
    }
}