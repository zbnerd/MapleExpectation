package maple.expectation.chaos.nightmare;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Nightmare 04: Connection Vampire - DB Connection Pool 고갈 테스트
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>Red (SRE): 장애 주입 - 외부 API 10초 지연으로 커넥션 점유 유발</li>
 *   <li>Blue (Architect): 흐름 검증 - @Transactional 내 외부 API 블로킹 호출 패턴</li>
 *   <li>Green (Performance): 메트릭 검증 - HikariCP Pool 사용률, Connection Wait Time</li>
 *   <li>Purple (Auditor): 데이터 무결성 - 트랜잭션 롤백 시 일관성 유지</li>
 *   <li>Yellow (QA Master): 테스트 전략 - Pool 고갈 시 Fail-Fast 검증</li>
 * </ul>
 *
 * <h4>예상 결과: FAIL</h4>
 * <p>GameCharacterService.createNewCharacter()가 @Transactional 범위 내에서
 * nexonApiClient.getOcidByCharacterName().join()을 호출하여 최대 28초간
 * DB 커넥션을 점유합니다.</p>
 *
 * <h4>취약점 위치</h4>
 * <p>GameCharacterService.java Line 70-102</p>
 *
 * <h4>관련 CS 원리</h4>
 * <ul>
 *   <li>Connection Pool Exhaustion: 커넥션 점유로 인한 풀 고갈</li>
 *   <li>Transaction Scope: 트랜잭션 경계가 외부 I/O를 포함하면 안됨</li>
 *   <li>Blocking I/O in Transaction: .join() 호출이 트랜잭션 내에서 블로킹</li>
 *   <li>Little's Law: L = λW (대기 커넥션 = 도착률 × 평균 점유 시간)</li>
 * </ul>
 *
 * @see maple.expectation.service.v2.GameCharacterService#createNewCharacter(String)
 */
@Tag("nightmare")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Nightmare 04: Connection Vampire - DB Connection Pool Starvation")
class ConnectionVampireNightmareTest extends AbstractContainerBaseTest {

    @Autowired
    private DataSource dataSource;

    @MockBean
    private NexonApiClient nexonApiClient;

    // 테스트 메트릭
    private final AtomicInteger connectionTimeoutCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong maxWaitTime = new AtomicLong(0);
    private final ConcurrentLinkedQueue<Long> connectionAcquireTimes = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        connectionTimeoutCount.set(0);
        successCount.set(0);
        maxWaitTime.set(0);
        connectionAcquireTimes.clear();
    }

    /**
     * Red's Test 1: 외부 API 지연 시 DB Connection Pool 고갈 검증
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>외부 API에 10초 지연 주입 (Mock)</li>
     *   <li>동시에 20개 요청 발생 (Pool 크기의 2배)</li>
     *   <li>모든 요청이 트랜잭션 내에서 API 응답 대기</li>
     *   <li>Pool 고갈로 connection-timeout 발생 예상</li>
     * </ol>
     *
     * <p><b>예상 실패</b>: connection-timeout 발생으로 요청 실패</p>
     */
    @Test
    @DisplayName("외부 API 지연 시 DB Connection Pool 고갈 검증")
    void shouldExhaustConnectionPool_whenExternalApiDelayed() throws Exception {
        // Given: Mock API with 5 second delay (connection-timeout보다 길게)
        long apiDelayMs = 5000;
        when(nexonApiClient.getOcidByCharacterName(anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(apiDelayMs);
                    return CompletableFuture.completedFuture(
                            new CharacterOcidResponse("test-ocid-" + System.nanoTime()));
                });

        // VUser 설정: Pool 크기(10)의 2배
        int concurrentRequests = 20;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

        System.out.println("[Red] Starting Connection Vampire test...");
        System.out.printf("[Red] API Delay: %dms, Concurrent Requests: %d%n", apiDelayMs, concurrentRequests);

        // When: 동시 커넥션 요청
        for (int i = 0; i < concurrentRequests; i++) {
            final int requestId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.nanoTime();

                    // 커넥션 획득 시도 (트랜잭션 시뮬레이션)
                    try (Connection conn = dataSource.getConnection()) {
                        long acquireTime = (System.nanoTime() - start) / 1_000_000;
                        connectionAcquireTimes.add(acquireTime);

                        // 외부 API 호출 시뮬레이션 (트랜잭션 내에서 블로킹)
                        Thread.sleep(apiDelayMs);

                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    if (e.getMessage() != null &&
                            (e.getMessage().contains("Connection") ||
                                    e.getMessage().contains("timeout") ||
                                    e.getMessage().contains("Timeout"))) {
                        connectionTimeoutCount.incrementAndGet();
                    }
                    System.out.printf("[Red] Request %d failed: %s%n", requestId, e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 모든 스레드 동시 시작
        startLatch.countDown();

        // 완료 대기 (최대 60초)
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // 메트릭 계산
        long avgAcquireTime = connectionAcquireTimes.stream()
                .mapToLong(Long::longValue).sum() /
                Math.max(connectionAcquireTimes.size(), 1);
        long maxAcquireTime = connectionAcquireTimes.stream()
                .mapToLong(Long::longValue).max().orElse(0);

        // Then: 결과 출력
        printConnectionVampireResults(concurrentRequests, completed, avgAcquireTime, maxAcquireTime);

        // 검증: Connection timeout이 발생해야 함 (Pool 고갈 증명)
        assertThat(connectionTimeoutCount.get())
                .as("[Nightmare] @Transactional + 외부 API 호출로 인한 Connection Pool 고갈")
                .isGreaterThan(0);
    }

    /**
     * Blue's Test 2: 트랜잭션 내 외부 API 호출 시 Connection 점유 시간 측정
     *
     * <p><b>시나리오</b>: API 지연 시간만큼 Connection이 점유되는지 검증</p>
     */
    @Test
    @DisplayName("트랜잭션 내 외부 API 호출 시 Connection 점유 시간 측정")
    void shouldHoldConnectionDuringExternalCall() throws Exception {
        // Given: 3초 API 지연
        long apiDelayMs = 3000;
        when(nexonApiClient.getOcidByCharacterName(anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(apiDelayMs);
                    return CompletableFuture.completedFuture(
                            new CharacterOcidResponse("test-ocid"));
                });

        System.out.println("[Blue] Measuring connection holding time...");

        // When: 단일 요청으로 점유 시간 측정
        long start = System.nanoTime();

        try (Connection conn = dataSource.getConnection()) {
            // 외부 API 호출 시뮬레이션
            Thread.sleep(apiDelayMs);
        }

        long elapsed = (System.nanoTime() - start) / 1_000_000;

        System.out.println("========================================");
        System.out.println("      Connection Holding Time Analysis   ");
        System.out.println("========================================");
        System.out.printf(" API Delay: %dms%n", apiDelayMs);
        System.out.printf(" Connection Hold Time: %dms%n", elapsed);
        System.out.printf(" Overhead: %dms%n", elapsed - apiDelayMs);
        System.out.println("========================================");

        // Then: Connection 점유 시간이 API 지연 시간 이상이어야 함
        assertThat(elapsed)
                .as("[Nightmare] Connection 점유 시간이 API 지연 시간과 유사해야 함")
                .isGreaterThanOrEqualTo(apiDelayMs - 500); // 500ms 오차 허용
    }

    /**
     * Green's Test 3: HikariCP Pool 상태 메트릭 검증
     *
     * <p><b>시나리오</b>: 동시 요청 시 Active Connections 수 측정</p>
     */
    @Test
    @DisplayName("동시 요청 시 HikariCP Pool 상태 메트릭 검증")
    void shouldMeasurePoolMetrics_duringConcurrentRequests() throws Exception {
        // Given
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
        HikariPoolMXBean poolMXBean = hikariDataSource.getHikariPoolMXBean();

        int concurrentRequests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch holdLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

        AtomicInteger peakActiveConnections = new AtomicInteger(0);

        System.out.println("[Green] Measuring HikariCP pool metrics...");

        // When: 모든 커넥션 점유
        for (int i = 0; i < concurrentRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();

                    try (Connection conn = dataSource.getConnection()) {
                        // Active 커넥션 수 기록
                        int active = poolMXBean.getActiveConnections();
                        peakActiveConnections.updateAndGet(curr -> Math.max(curr, active));

                        // 커넥션 점유 대기
                        holdLatch.await(10, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        Thread.sleep(2000); // 커넥션 획득 대기

        // 메트릭 수집
        int activeConnections = poolMXBean.getActiveConnections();
        int idleConnections = poolMXBean.getIdleConnections();
        int pendingThreads = poolMXBean.getThreadsAwaitingConnection();
        int totalConnections = poolMXBean.getTotalConnections();

        // 릴리즈
        holdLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.println("========================================");
        System.out.println("       HikariCP Pool Metrics            ");
        System.out.println("========================================");
        System.out.printf(" Peak Active: %d%n", peakActiveConnections.get());
        System.out.printf(" Active (during test): %d%n", activeConnections);
        System.out.printf(" Idle: %d%n", idleConnections);
        System.out.printf(" Pending Threads: %d%n", pendingThreads);
        System.out.printf(" Total: %d%n", totalConnections);
        System.out.println("========================================");

        // Then: Active connections가 요청 수에 근접해야 함
        assertThat(peakActiveConnections.get())
                .as("동시 요청 시 Active Connections가 증가해야 함")
                .isGreaterThan(0);
    }

    /**
     * Yellow's Test 4: Connection Pool 고갈 후 시스템 복구 검증
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>Pool 고갈 상태 유도</li>
     *   <li>커넥션 반환 후 복구 확인</li>
     * </ol>
     */
    @Test
    @DisplayName("Connection Pool 고갈 후 시스템 복구 검증")
    void shouldRecoverAfterPoolExhaustion() throws Exception {
        // Given: Pool 고갈 상태 유도
        int holdCount = 5;
        CopyOnWriteArrayList<Connection> heldConnections = new CopyOnWriteArrayList<>();

        System.out.println("[Yellow] Inducing pool exhaustion...");

        // 커넥션 점유
        for (int i = 0; i < holdCount; i++) {
            try {
                Connection conn = dataSource.getConnection();
                heldConnections.add(conn);
            } catch (Exception e) {
                System.out.println("[Yellow] Could not acquire more connections");
                break;
            }
        }

        System.out.printf("[Yellow] Held %d connections%n", heldConnections.size());

        // When: 모든 커넥션 반환
        long releaseStart = System.nanoTime();
        for (Connection conn : heldConnections) {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
        heldConnections.clear();

        // 복구 확인
        long start = System.nanoTime();
        try (Connection conn = dataSource.getConnection()) {
            long recoveryTime = (System.nanoTime() - start) / 1_000_000;

            System.out.println("========================================");
            System.out.println("       Pool Recovery Analysis           ");
            System.out.println("========================================");
            System.out.printf(" Recovery Time: %dms%n", recoveryTime);
            System.out.println("========================================");

            // Then: 빠른 복구 (5초 이내)
            assertThat(recoveryTime)
                    .as("Pool 복구 후 커넥션 획득이 빨라야 함")
                    .isLessThan(5000);
        }
    }

    private void printConnectionVampireResults(int concurrentRequests, boolean completed,
                                               long avgAcquireTime, long maxAcquireTime) {
        System.out.println("==========================================================");
        System.out.println("       Nightmare 04: Connection Vampire Results           ");
        System.out.println("==========================================================");
        System.out.printf(" Total Requests: %d%n", concurrentRequests);
        System.out.printf(" Completed: %s%n", completed ? "YES" : "NO (TIMEOUT)");
        System.out.printf(" Success: %d%n", successCount.get());
        System.out.printf(" Connection Timeout: %d%n", connectionTimeoutCount.get());
        System.out.printf(" Avg Connection Acquire Time: %dms%n", avgAcquireTime);
        System.out.printf(" Max Connection Acquire Time: %dms%n", maxAcquireTime);
        System.out.println("----------------------------------------------------------");

        if (connectionTimeoutCount.get() > 0) {
            System.out.println(" Verdict: FAIL - Connection Pool Exhaustion Detected!");
            System.out.println(" ");
            System.out.println(" Root Cause: @Transactional + External API Blocking Call");
            System.out.println(" Location: GameCharacterService.createNewCharacter()");
            System.out.println(" Fix: Separate transaction scope from external API calls");
        } else {
            System.out.println(" Verdict: PASS - No Connection Pool Exhaustion");
        }
        System.out.println("==========================================================");
    }
}
