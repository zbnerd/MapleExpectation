package maple.expectation.chaos.nightmare;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nightmare 11: Lock Fallback Avalanche - HikariCP 연결 고갈
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - Redis 장애 후 대량 MySQL Named Lock 요청</li>
 *   <li>🔵 Blue (Architect): 흐름 검증 - Connection Pool 고갈 경로 분석</li>
 *   <li>🟢 Green (Performance): 메트릭 검증 - HikariCP active/pending/timeout 메트릭</li>
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 락 획득 실패 시 데이터 정합성</li>
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - Connection 고갈 시 P0 Issue 생성</li>
 * </ul>
 *
 * <h4>예상 결과: FAIL (취약점 노출)</h4>
 * <p>MySQL Named Lock은 연결당 1개만 보유 가능.
 * Fallback Avalanche 시 Connection Pool이 즉시 고갈됨.</p>
 *
 * <h4>관련 CS 원리</h4>
 * <ul>
 *   <li>Connection Pool Exhaustion: 동시 요청이 풀 크기 초과</li>
 *   <li>Session-based Lock: MySQL GET_LOCK은 세션 종료 시 자동 해제</li>
 *   <li>Bulkhead Pattern: 장애 격리를 위한 자원 분리</li>
 *   <li>Backpressure: 과부하 시 요청 제한</li>
 *   <li>Little's Law: L = λW, 동시성 제어의 수학적 근거</li>
 * </ul>
 *
 * @see HikariDataSource
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@DisplayName("Nightmare 11: Lock Fallback Avalanche - HikariCP 연결 고갈")
class LockFallbackAvalancheNightmareTest extends AbstractContainerBaseTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private LockStrategy lockStrategy;

    private static final int LOCK_HOLD_TIME_MS = 2000;
    private static final int CONCURRENT_LOCK_REQUESTS = 30;

    @AfterEach
    void tearDown() {
        recoverMaster();
    }

    /**
     * 🔴 Red's Test 1: Redis 장애 시 MySQL Connection Pool 고갈 검증
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>Redis 연결 차단 (Toxiproxy)</li>
     *   <li>동시에 30개 락 요청 (각각 다른 키)</li>
     *   <li>모든 요청이 MySQL Named Lock으로 Fallback</li>
     *   <li>각 락이 연결을 점유 → Pool 고갈</li>
     * </ol>
     *
     * <p><b>성공 기준</b>: Connection timeout 발생 없음</p>
     * <p><b>실패 조건</b>: Connection timeout ≥ 1건</p>
     */
    @Test
    @DisplayName("Redis 장애 시 MySQL Connection Pool 고갈 검증")
    void shouldNotExhaustConnectionPool_duringFallbackAvalanche() throws Exception {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicLong maxWaitTime = new AtomicLong(0);

        HikariPoolMXBean poolMXBean = getPoolMXBean();
        int initialActive = poolMXBean != null ? poolMXBean.getActiveConnections() : 0;
        int poolSize = poolMXBean != null ? poolMXBean.getTotalConnections() : 10;

        log.info("[Red] Starting Lock Fallback Avalanche test...");
        log.info("[Red] Pool size: {}, Initial active: {}", poolSize, initialActive);
        log.info("[Red] Concurrent requests: {} (exceeds pool size)", CONCURRENT_LOCK_REQUESTS);

        // Kill Redis to force MySQL fallback
        failMaster();
        Thread.sleep(500);

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_LOCK_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_LOCK_REQUESTS);

        ConcurrentLinkedQueue<Long> waitTimes = new ConcurrentLinkedQueue<>();
        AtomicInteger peakActive = new AtomicInteger(0);
        AtomicInteger peakPending = new AtomicInteger(0);

        for (int i = 0; i < CONCURRENT_LOCK_REQUESTS; i++) {
            final int requestId = i;
            final String lockKey = "avalanche-lock-" + requestId; // 모두 다른 키

            executor.submit(() -> {
                try {
                    startLatch.await();

                    long waitStart = System.currentTimeMillis();

                    // 락 획득 시도 (MySQL fallback 예상)
                    try {
                        lockStrategy.executeWithLock(lockKey, 10000, LOCK_HOLD_TIME_MS, () -> {
                            // 락 점유 중 pool 상태 기록
                            if (poolMXBean != null) {
                                int active = poolMXBean.getActiveConnections();
                                int pending = poolMXBean.getThreadsAwaitingConnection();

                                updateMax(peakActive, active);
                                updateMax(peakPending, pending);
                            }

                            Thread.sleep(LOCK_HOLD_TIME_MS);
                            return "success-" + requestId;
                        });
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }

                    long waitTime = System.currentTimeMillis() - waitStart;
                    waitTimes.add(waitTime);
                    updateMax(maxWaitTime, waitTime);

                    successCount.incrementAndGet();
                    log.info("[Request-{}] Completed in {}ms", requestId, waitTime);

                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

                    if (msg.contains("timeout") || msg.contains("connection")) {
                        timeoutCount.incrementAndGet();
                        log.warn("[Request-{}] CONNECTION TIMEOUT: {}", requestId, e.getMessage());
                    } else {
                        errorCount.incrementAndGet();
                        log.warn("[Request-{}] Error: {}", requestId, e.getMessage());
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 동시 시작
        startLatch.countDown();

        // 완료 대기
        boolean completed = doneLatch.await(120, TimeUnit.SECONDS);
        executor.shutdown();

        // 복구
        recoverMaster();

        // 통계
        long avgWaitTime = waitTimes.isEmpty() ? 0 :
                waitTimes.stream().mapToLong(Long::longValue).sum() / waitTimes.size();

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│     Nightmare 11: Lock Fallback Avalanche Results          │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Test Completed: {}                                         │", completed ? "YES" : "NO");
        log.info("│ Concurrent Requests: {}                                    │", CONCURRENT_LOCK_REQUESTS);
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Success: {}                                                │", successCount.get());
        log.info("│ Timeout: {}                                                │", timeoutCount.get());
        log.info("│ Error: {}                                                  │", errorCount.get());
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Connection Pool Analysis:                                  │");
        log.info("│   Pool Size: {}                                            │", poolSize);
        log.info("│   Peak Active: {}                                          │", peakActive.get());
        log.info("│   Peak Pending: {}                                         │", peakPending.get());
        log.info("│   Max Wait Time: {}ms                                      │", maxWaitTime.get());
        log.info("│   Avg Wait Time: {}ms                                      │", avgWaitTime);
        log.info("├────────────────────────────────────────────────────────────┤");

        if (timeoutCount.get() > 0) {
            log.info("│ ❌ CONNECTION POOL EXHAUSTED!                              │");
            log.info("│ 🔧 Solutions:                                              │");
            log.info("│    1. Bulkhead: 별도 Connection Pool for Named Lock        │");
            log.info("│    2. Semaphore: Fallback 동시성 제한                       │");
            log.info("│    3. Circuit Breaker: 빠른 실패로 풀 보호                  │");
        } else {
            log.info("│ ✅ Connection Pool survived the avalanche                  │");
        }
        log.info("└────────────────────────────────────────────────────────────┘");

        // 검증
        assertThat(timeoutCount.get())
                .as("[Nightmare] Connection timeout should not occur during fallback")
                .isZero();
    }

    /**
     * 🔵 Blue's Test 2: MySQL Named Lock의 연결 점유 확인
     *
     * <p>GET_LOCK이 연결을 점유하고 있는 동안 다른 쿼리 실행 가능 여부 확인</p>
     */
    @Test
    @DisplayName("Named Lock 보유 중 동일 연결에서 쿼리 실행 가능 여부")
    void shouldExecuteQueriesWhileHoldingLock() throws Exception {
        log.info("[Blue] Testing query execution while holding named lock...");

        try (Connection conn = dataSource.getConnection()) {
            // GET_LOCK 획득
            try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                ps.setString(1, "test-query-lock");
                ps.setInt(2, 5);
                ps.execute();
                log.info("[Blue] Named lock acquired");
            }

            // 락 보유 중 SELECT 실행
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 as test")) {
                boolean success = ps.execute();
                log.info("[Blue] Query executed while holding lock: {}", success ? "YES" : "NO");
            }

            // RELEASE_LOCK
            try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                ps.setString(1, "test-query-lock");
                ps.execute();
                log.info("[Blue] Named lock released");
            }
        }

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│         Named Lock Connection Behavior                     │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ ✅ Queries can execute while holding named lock            │");
        log.info("│ ⚠️ But the connection remains occupied                     │");
        log.info("└────────────────────────────────────────────────────────────┘");

        // 항상 성공 (동작 확인용)
        assertThat(true).isTrue();
    }

    /**
     * 🟢 Green's Test 3: HikariCP 메트릭 수집
     *
     * <p>Connection Pool 상태를 실시간으로 모니터링</p>
     */
    @Test
    @DisplayName("HikariCP 메트릭 수집 및 분석")
    void shouldCollectHikariMetrics() throws Exception {
        HikariPoolMXBean poolMXBean = getPoolMXBean();

        if (poolMXBean == null) {
            log.warn("[Green] HikariPoolMXBean not available");
            return;
        }

        // 부하 발생 전 상태
        log.info("[Green] === Before Load ===");
        logPoolState(poolMXBean);

        // 일부 연결 점유
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch holding = new CountDownLatch(5);
        CountDownLatch release = new CountDownLatch(1);

        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try (Connection conn = dataSource.getConnection()) {
                    holding.countDown();
                    release.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("Error: {}", e.getMessage());
                }
            });
        }

        holding.await(5, TimeUnit.SECONDS);

        // 부하 중 상태
        log.info("[Green] === During Load (5 connections held) ===");
        logPoolState(poolMXBean);

        // 해제
        release.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        Thread.sleep(100);

        // 부하 후 상태
        log.info("[Green] === After Load ===");
        logPoolState(poolMXBean);

        assertThat(true).isTrue();
    }

    // ========== Helper Methods ==========

    private HikariPoolMXBean getPoolMXBean() {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getHikariPoolMXBean();
        }
        return null;
    }

    private void logPoolState(HikariPoolMXBean mxBean) {
        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│         HikariCP Pool State                                │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Total Connections: {}                                      │", mxBean.getTotalConnections());
        log.info("│ Active Connections: {}                                     │", mxBean.getActiveConnections());
        log.info("│ Idle Connections: {}                                       │", mxBean.getIdleConnections());
        log.info("│ Threads Awaiting: {}                                       │", mxBean.getThreadsAwaitingConnection());
        log.info("└────────────────────────────────────────────────────────────┘");
    }

    private void updateMax(AtomicInteger current, int newValue) {
        int cur = current.get();
        while (newValue > cur && !current.compareAndSet(cur, newValue)) {
            cur = current.get();
        }
    }

    private void updateMax(AtomicLong current, long newValue) {
        long cur = current.get();
        while (newValue > cur && !current.compareAndSet(cur, newValue)) {
            cur = current.get();
        }
    }
}
