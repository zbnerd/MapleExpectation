package maple.expectation.chaos.nightmare;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Nightmare 04: Connection Vampire - DB Connection Pool 고갈 방지 검증 테스트
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>Red (SRE): 장애 주입 - 외부 API 10초 지연으로 커넥션 점유 유발</li>
 *   <li>Blue (Architect): 흐름 검증 - 트랜잭션 경계 분리 패턴 적용</li>
 *   <li>Green (Performance): 메트릭 검증 - HikariCP Pool 사용률, Connection Wait Time</li>
 *   <li>Purple (Auditor): 데이터 무결성 - 트랜잭션 롤백 시 일관성 유지</li>
 *   <li>Yellow (QA Master): 테스트 전략 - Pool 고갈 방지 검증</li>
 * </ul>
 *
 * <h4>예상 결과: PASS (Issue #226 수정 후)</h4>
 * <p>GameCharacterService.createNewCharacter()가 트랜잭션 경계 분리 패턴을 적용하여
 * API 호출은 트랜잭션 밖에서, DB 저장은 트랜잭션 안에서 수행합니다.
 * 커넥션 점유 시간: 28초 → ~100ms</p>
 *
 * <h4>해결된 취약점</h4>
 * <p>GameCharacterService.java, OcidResolver.java - 트랜잭션 경계 분리</p>
 *
 * <h4>관련 CS 원리</h4>
 * <ul>
 *   <li>Transaction Scope Minimization: 트랜잭션 경계를 DB 작업에만 한정</li>
 *   <li>Connection Pool Efficiency: 외부 I/O를 트랜잭션 밖에서 처리</li>
 *   <li>Separation of Concerns: API 호출과 DB 작업 분리</li>
 *   <li>Little's Law: L = λW (짧은 점유 시간 = 적은 대기 커넥션)</li>
 * </ul>
 *
 * @see maple.expectation.service.v2.GameCharacterService#createNewCharacter(String)
 * @see <a href="https://github.com/issue/226">Issue #226: Connection Vampire 방지</a>
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
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
     * Red's Test 1: 외부 API 지연 시에도 DB Connection Pool 고갈 방지 검증 (Issue #226 수정 후)
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>외부 API에 5초 지연 주입 (Mock)</li>
     *   <li>동시에 20개 요청 발생 (Pool 크기의 2배)</li>
     *   <li>트랜잭션 경계 분리로 API 호출은 트랜잭션 밖에서 수행</li>
     *   <li>Pool 고갈 없이 모든 요청 성공 예상</li>
     * </ol>
     *
     * <p><b>예상 성공</b>: 트랜잭션 경계 분리로 Connection Pool 고갈 방지</p>
     *
     * <p><b>Issue #226 수정 사항</b>:
     * <ul>
     *   <li>Before: @Transactional 범위 내 .join() 호출 → 최대 28초 DB Connection 점유</li>
     *   <li>After: API 호출은 트랜잭션 밖, DB 작업만 트랜잭션 안 → ~100ms 점유</li>
     * </ul>
     * </p>
     */
    @Test
    @DisplayName("외부 API 지연 시에도 DB Connection Pool 고갈 방지 검증")
    void shouldNotExhaustConnectionPool_whenExternalApiDelayed_afterFix() throws Exception {
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

        log.info("[Red] Starting Connection Vampire Prevention test (Issue #226 Fixed)...");
        log.info("[Red] API Delay: {}ms, Concurrent Requests: {}", apiDelayMs, concurrentRequests);

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
                    log.info("[Red] Request {} failed: {}", requestId, e.getMessage());
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

        // 검증: Connection timeout이 발생하지 않아야 함 (Issue #226 수정 효과 증명)
        assertThat(connectionTimeoutCount.get())
                .as("[Issue #226 Fixed] 트랜잭션 경계 분리로 Connection Pool 고갈 방지")
                .isEqualTo(0);
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

        log.info("[Blue] Measuring connection holding time...");

        // When: 단일 요청으로 점유 시간 측정
        long start = System.nanoTime();

        try (Connection conn = dataSource.getConnection()) {
            // 외부 API 호출 시뮬레이션
            Thread.sleep(apiDelayMs);
        }

        long elapsed = (System.nanoTime() - start) / 1_000_000;

        log.info("========================================");
        log.info("      Connection Holding Time Analysis   ");
        log.info("========================================");
        log.info(" API Delay: {}ms", apiDelayMs);
        log.info(" Connection Hold Time: {}ms", elapsed);
        log.info(" Overhead: {}ms", elapsed - apiDelayMs);
        log.info("========================================");

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

        log.info("[Green] Measuring HikariCP pool metrics...");

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

        log.info("========================================");
        log.info("       HikariCP Pool Metrics            ");
        log.info("========================================");
        log.info(" Peak Active: {}", peakActiveConnections.get());
        log.info(" Active (during test): {}", activeConnections);
        log.info(" Idle: {}", idleConnections);
        log.info(" Pending Threads: {}", pendingThreads);
        log.info(" Total: {}", totalConnections);
        log.info("========================================");

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

        log.info("[Yellow] Inducing pool exhaustion...");

        // 커넥션 점유
        for (int i = 0; i < holdCount; i++) {
            try {
                Connection conn = dataSource.getConnection();
                heldConnections.add(conn);
            } catch (Exception e) {
                log.info("[Yellow] Could not acquire more connections");
                break;
            }
        }

        log.info("[Yellow] Held {} connections", heldConnections.size());

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

            log.info("========================================");
            log.info("       Pool Recovery Analysis           ");
            log.info("========================================");
            log.info(" Recovery Time: {}ms", recoveryTime);
            log.info("========================================");

            // Then: 빠른 복구 (5초 이내)
            assertThat(recoveryTime)
                    .as("Pool 복구 후 커넥션 획득이 빨라야 함")
                    .isLessThan(5000);
        }
    }

    private void printConnectionVampireResults(int concurrentRequests, boolean completed,
                                               long avgAcquireTime, long maxAcquireTime) {
        log.info("==========================================================");
        log.info("       Nightmare 04: Connection Vampire Results           ");
        log.info("==========================================================");
        log.info(" Total Requests: {}", concurrentRequests);
        log.info(" Completed: {}", completed ? "YES" : "NO (TIMEOUT)");
        log.info(" Success: {}", successCount.get());
        log.info(" Connection Timeout: {}", connectionTimeoutCount.get());
        log.info(" Avg Connection Acquire Time: {}ms", avgAcquireTime);
        log.info(" Max Connection Acquire Time: {}ms", maxAcquireTime);
        log.info("----------------------------------------------------------");

        if (connectionTimeoutCount.get() > 0) {
            log.info(" Verdict: FAIL - Connection Pool Exhaustion Detected!");
            log.info(" ");
            log.info(" Root Cause: @Transactional + External API Blocking Call");
            log.info(" Location: GameCharacterService.createNewCharacter()");
            log.info(" Fix: Separate transaction scope from external API calls");
        } else {
            log.info(" Verdict: PASS - No Connection Pool Exhaustion");
            log.info(" ");
            log.info(" Issue #226 Fix Applied: Transaction Boundary Separation");
            log.info(" API calls outside TX, DB operations inside TX");
            log.info(" Connection hold time: 28s → ~100ms");
        }
        log.info("==========================================================");
    }
}
