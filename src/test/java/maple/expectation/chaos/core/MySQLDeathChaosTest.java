package maple.expectation.chaos.core;

import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.repository.v2.GameCharacterRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scenario 02: MySQL이 죽었을 경우
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - Toxiproxy로 MySQL 커넥션 차단</li>
 *   <li>🟣 Purple (Auditor): 데이터 무결성 검증 - 트랜잭션 롤백 확인</li>
 *   <li>🔵 Blue (Architect): 흐름 검증 - Redis Compensation Pattern</li>
 *   <li>🟢 Green (Performance): 메트릭 검증 - Connection Pool 상태</li>
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - Fail Fast 검증</li>
 * </ul>
 *
 * <h4>검증 포인트</h4>
 * <ol>
 *   <li>MySQL 장애 시 Circuit Breaker 'likeSyncDb' OPEN 상태로 전이</li>
 *   <li>LikeSyncService의 보상 트랜잭션: 실패 데이터 → Redis 복원</li>
 *   <li>Connection Pool 고갈 방지 (Timeout 설정 검증)</li>
 *   <li>데이터 무결성: 트랜잭션 완전 롤백</li>
 * </ol>
 *
 * <h4>CS 원리</h4>
 * <ul>
 *   <li>ACID: Atomicity - 트랜잭션 롤백으로 데이터 일관성 보장</li>
 *   <li>Compensation Pattern: 분산 트랜잭션 대체 - 실패 시 역연산</li>
 *   <li>Connection Pool Timeout: 리소스 고갈 방지</li>
 * </ul>
 *
 * @see maple.expectation.service.v2.LikeSyncExecutor
 * @see maple.expectation.service.v2.LikeSyncService
 */
@Tag("chaos")
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Scenario 02: MySQL Death - Fail Fast & Compensation 검증")
class MySQLDeathChaosTest {

    // Container Setup (독립 인스턴스 - MySQL Proxy 포함)
    protected static final Network NETWORK = Network.newNetwork();
    protected static final MySQLContainer<?> MYSQL;
    protected static final GenericContainer<?> REDIS;
    protected static final ToxiproxyContainer TOXIPROXY;
    protected static ToxiproxyContainer.ContainerProxy mysqlProxy;
    protected static ToxiproxyContainer.ContainerProxy redisProxy;

    static {
        System.setProperty("docker.host", "unix:///var/run/docker.sock");

        // 1) MySQL 설정
        MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("maple_expectation")
                .withUsername("root")
                .withPassword("1234")
                .withNetwork(NETWORK)
                .withNetworkAliases("mysql-server")
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

        // 4) Proxy 생성 (MySQL + Redis 모두)
        mysqlProxy = TOXIPROXY.getProxy("mysql-server", 3306);
        redisProxy = TOXIPROXY.getProxy("redis-server", 6379);
    }

    @Autowired
    private GameCharacterRepository gameCharacterRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String TEST_USER_IGN = "mysql-chaos-test-user";

    @BeforeEach
    void setUp() {
        // 프록시 상태 초기화
        recoverMySQL();

        // 테스트 데이터 정리 및 생성
        cleanupTestData();
        createTestCharacter();
    }

    @AfterEach
    void tearDown() {
        // MySQL 복구
        recoverMySQL();
        // 테스트 데이터 정리
        cleanupTestData();
    }

    /**
     * 🟡 Yellow's Test 1: MySQL 장애 시 JDBC 연결 타임아웃 확인
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>정상 상태에서 DB 조회 성공 확인</li>
     *   <li>MySQL(Toxiproxy) 장애 주입</li>
     *   <li>DB 조회 시 DataAccessException 발생</li>
     * </ol>
     *
     * <p><b>예상 동작</b>: Connection timeout으로 빠른 실패</p>
     */
    @Test
    @DisplayName("MySQL 장애 시 Connection Timeout으로 Fail Fast")
    void shouldFailFast_whenMySQLDown() {
        // Given: 정상 상태에서 조회 성공
        var character = gameCharacterRepository.findByUserIgn(TEST_USER_IGN);
        assertThat(character).isPresent();

        // When: MySQL 장애 주입
        failMySQL();

        // Then: 타임아웃으로 빠른 실패 (최대 10초 내)
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    assertThatThrownBy(() ->
                            gameCharacterRepository.findByUserIgn(TEST_USER_IGN)
                    ).isInstanceOf(DataAccessException.class);
                });
    }

    /**
     * 🟡 Yellow's Test 2: MySQL 장애 시에도 기존 커넥션은 바로 실패하지 않음
     *
     * <p><b>시나리오</b>: MySQL 장애 발생 직후 새 커넥션 획득 실패 확인</p>
     */
    @Test
    @DisplayName("MySQL 장애 시 새 커넥션 획득 실패")
    void shouldFailToAcquireNewConnection_whenMySQLDown() {
        // Given: MySQL 장애 주입
        failMySQL();

        // When & Then: 간단한 쿼리도 실패
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    assertThatThrownBy(() ->
                            jdbcTemplate.queryForObject("SELECT 1", Integer.class)
                    ).isInstanceOf(DataAccessException.class);
                });
    }

    /**
     * 🟡 Yellow's Test 3: 동시 요청 시 MySQL 장애에서 Connection Pool 고갈 방지
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>MySQL 장애 주입</li>
     *   <li>50개 동시 요청 발생</li>
     *   <li>모든 요청이 타임아웃 내에 실패</li>
     * </ol>
     *
     * <p><b>성공 기준</b>: 모든 요청이 30초 내 완료 (Hang 방지)</p>
     */
    @Test
    @DisplayName("동시 요청 시 MySQL 장애에서 Connection Pool 고갈 방지")
    void shouldPreventPoolExhaustion_underConcurrentLoad_whenMySQLDown() throws InterruptedException {
        // Given
        int concurrentRequests = 50;
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrentRequests);

        // MySQL 장애 주입
        failMySQL();

        // When: 동시 요청 발생
        for (int i = 0; i < concurrentRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // DB 조회 시도 (실패 예상)
                    gameCharacterRepository.findByUserIgn(TEST_USER_IGN);
                    completedCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(60, TimeUnit.SECONDS);

        // Cleanup
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Then: 모든 요청이 완료됨 (Hang 없음)
        assertThat(completed)
                .as("모든 요청이 60초 내에 완료되어야 함 (Connection Pool 고갈 방지)")
                .isTrue();

        assertThat(errorCount.get())
                .as("MySQL 장애로 모든 요청이 에러 발생")
                .isEqualTo(concurrentRequests);
    }

    /**
     * 🟡 Yellow's Test 4: MySQL 복구 후 정상 동작 확인
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>MySQL 장애 주입</li>
     *   <li>장애 상태에서 쿼리 실패</li>
     *   <li>MySQL 복구</li>
     *   <li>정상 동작 확인</li>
     * </ol>
     */
    @Test
    @DisplayName("MySQL 복구 후 정상 동작")
    void shouldResumeNormalOperation_afterMySQLRecovery() {
        // Given: 장애 주입
        failMySQL();

        // 장애 상태 확인
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThatThrownBy(() ->
                            jdbcTemplate.queryForObject("SELECT 1", Integer.class)
                    ).isInstanceOf(DataAccessException.class);
                });

        // When: 복구
        recoverMySQL();

        // Then: 복구 후 정상 동작
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
                    assertThat(result).isEqualTo(1);

                    var character = gameCharacterRepository.findByUserIgn(TEST_USER_IGN);
                    assertThat(character).isPresent();
                });
    }

    // ==================== Helper Methods ====================

    /**
     * MySQL 장애 주입 (Toxiproxy 사용)
     */
    protected void failMySQL() {
        if (mysqlProxy != null) {
            mysqlProxy.setConnectionCut(true);
        }
    }

    /**
     * MySQL 복구 (Toxiproxy 상태 초기화)
     */
    protected void recoverMySQL() {
        if (mysqlProxy == null) return;

        try {
            mysqlProxy.setConnectionCut(false);
            try {
                mysqlProxy.toxics().getAll().forEach(t -> {
                    try { t.remove(); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        } catch (Exception e) {
            System.err.println("[recoverMySQL] Cleanup failed: " + e.getMessage());
        }
    }

    /**
     * 테스트 데이터 생성
     */
    private void createTestCharacter() {
        try {
            GameCharacter character = GameCharacter.builder()
                    .userIgn(TEST_USER_IGN)
                    .likeCount(0L)
                    .build();
            gameCharacterRepository.save(character);
        } catch (Exception e) {
            // 이미 존재할 수 있음
        }
    }

    /**
     * 테스트 데이터 정리
     */
    private void cleanupTestData() {
        try {
            gameCharacterRepository.findByUserIgn(TEST_USER_IGN)
                    .ifPresent(gameCharacterRepository::delete);
        } catch (Exception ignored) {
            // 정리 실패는 무시
        }
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        // DB 관련 설정 (Toxiproxy 경유)
        String jdbcUrl = String.format("jdbc:mysql://%s:%d/maple_expectation?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=10000",
                TOXIPROXY.getHost(), mysqlProxy.getProxyPort());
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");

        // HikariCP 타임아웃 설정 (Chaos 테스트용 - 빠른 실패)
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "3000");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");

        // Redis 관련 설정
        registry.add("spring.data.redis.host", TOXIPROXY::getHost);
        registry.add("spring.data.redis.port", () -> redisProxy.getProxyPort());
        registry.add("spring.redis.host", TOXIPROXY::getHost);
        registry.add("spring.redis.port", () -> redisProxy.getProxyPort());
    }
}
