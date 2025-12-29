package maple.expectation.support;

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
 */
public abstract class AbstractContainerBaseTest {

    protected static final Network NETWORK = Network.newNetwork();

    protected static final MySQLContainer<?> MYSQL;
    protected static final GenericContainer<?> REDIS;
    protected static final ToxiproxyContainer TOXIPROXY;

    protected static ToxiproxyContainer.ContainerProxy redisProxy;

    static {
        // Docker 환경 강제 (WSL 환경 안정화 목적)
        System.setProperty("docker.host", "unix:///var/run/docker.sock");
        System.setProperty(
                "docker.client.strategy",
                "org.testcontainers.dockerclient.UnixSocketClientProviderStrategy"
        );

        // 1) MySQL
        MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("maple_expectation")
                .withUsername("root")
                .withPassword("1234")
                .withNetwork(NETWORK)
                .waitingFor(Wait.forLogMessage(".*ready for connections.*\\s", 2))
                .withStartupTimeout(Duration.ofMinutes(2));

        // 2) Redis
        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
                .withExposedPorts(6379)
                .withNetwork(NETWORK)
                .withNetworkAliases("redis-server")
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(1));

        // 3) Toxiproxy
        TOXIPROXY = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
                .withNetwork(NETWORK)
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(1));

        // 컨테이너 시작
        MYSQL.start();
        REDIS.start();
        TOXIPROXY.start();

        // redis-server(네트워크 alias) -> toxiproxy 프록시 생성
        redisProxy = TOXIPROXY.getProxy("redis-server", 6379);
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        // MySQL
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

        // ✅ Redis: 애플리케이션은 "Toxiproxy(호스트) + 프록시 포트(호스트 포트)"로 접속해야 함
        registry.add("spring.data.redis.host", TOXIPROXY::getHost);
        registry.add("spring.data.redis.port", redisProxy::getProxyPort); // ✅ 핵심 수정 (getMappedPort로 감싸지 말 것)

        // (선택) Redisson/레거시 설정이 spring.redis.* 를 참조하는 경우 대비
        registry.add("spring.redis.host", TOXIPROXY::getHost);
        registry.add("spring.redis.port", redisProxy::getProxyPort);
    }
}
