package maple.expectation.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
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
 * @AutoConfigureTestDatabase(replace = NONE): Spring이 DataSource를 H2로 강제 교체하는 것을 방지
 */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
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

    @AfterEach
    protected void globalProxyReset() {
        if (redisProxy != null) {
            for (int i = 0; i < 3; i++) {
                try {
                    redisProxy.toxics().getAll().forEach(t -> {
                        try { t.remove(); } catch (Exception ignored) {}
                    });
                    redisProxy.setConnectionCut(false);
                    return;
                } catch (Exception e) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
            }
        }
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

        // Redis 관련 설정
        registry.add("spring.data.redis.host", TOXIPROXY::getHost);
        registry.add("spring.data.redis.port", () -> redisProxy.getProxyPort());
        registry.add("spring.redis.host", TOXIPROXY::getHost);
        registry.add("spring.redis.port", () -> redisProxy.getProxyPort());
    }
}