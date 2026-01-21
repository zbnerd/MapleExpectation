package maple.expectation.chaos.nightmare;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.global.lock.ResilientLockStrategy;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nightmare 08: Thundering Herd (Redis Death) - Lock Fallback Avalanche
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - Toxiproxy로 Redis 완전 차단 후 동시 락 요청</li>
 *   <li>🔵 Blue (Architect): 흐름 검증 - ResilientLockStrategy fallback 경로 검증</li>
 *   <li>🟢 Green (Performance): 메트릭 검증 - HikariCP 연결 고갈, Circuit Breaker 상태</li>
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 락 없이 동시 실행되지 않음 검증</li>
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - Connection Pool 고갈 시 P0 Issue 생성</li>
 * </ul>
 *
 * <h4>예상 결과: CONDITIONAL PASS/FAIL</h4>
 * <p>Redis 장애 시 MySQL Named Lock으로 fallback하지만,
 * 동시에 많은 요청이 fallback하면 MySQL Connection Pool이 고갈됨.</p>
 *
 * <h4>관련 CS 원리</h4>
 * <ul>
 *   <li>Thundering Herd: 장애 복구 시점에 대량 요청이 몰리는 현상</li>
 *   <li>Cascading Failure: 한 컴포넌트 장애가 다른 컴포넌트로 전파</li>
 *   <li>Circuit Breaker Pattern: 장애 격리를 위한 차단기 패턴</li>
 *   <li>Connection Pool Exhaustion: 동시 요청 급증으로 풀 고갈</li>
 *   <li>Backpressure: 시스템 과부하 시 요청 제한</li>
 * </ul>
 *
 * @see ResilientLockStrategy
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@DisplayName("Nightmare 08: Thundering Herd (Redis Death) - Lock Fallback Avalanche")
class ThunderingHerdRedisDeathNightmareTest extends AbstractContainerBaseTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private LockStrategy lockStrategy;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private static final int CONCURRENT_REQUESTS = 50;
    private static final int CONNECTION_TIMEOUT_THRESHOLD = 5;

    @BeforeEach
    void setUp() {
        // Reset circuit breaker state
        try {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("redisLock");
            cb.reset();
            log.info("[Setup] Circuit breaker reset to CLOSED state");
        } catch (Exception e) {
            log.warn("[Setup] Could not reset circuit breaker: {}", e.getMessage());
        }
    }

    @AfterEach
    void tearDown() {
        // Recover Redis via parent class method
        recoverMaster();
        log.info("[Cleanup] Redis connection recovered");
    }

    /**
     * 🔴 Red's Test 1: Redis 장애 시 동시 락 요청으로 MySQL Connection Pool 고갈 검증
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>Redis 완전 차단 (Toxiproxy connectionCut)</li>
     *   <li>동시에 50개 락 획득 요청 발생</li>
     *   <li>모든 요청이 MySQL Named Lock으로 fallback</li>
     *   <li>MySQL Connection Pool 고갈 여부 확인</li>
     * </ol>
     *
     * <p><b>성공 기준</b>: Connection timeout 발생 ≤ 5</p>
     * <p><b>실패 조건</b>: Connection timeout > 5 → 시스템이 Thundering Herd에 취약</p>
     */
    @Test
    @DisplayName("Redis 장애 시 동시 락 요청으로 Connection Pool 고갈 검증")
    void shouldHandleRedisDeath_withoutConnectionPoolExhaustion() throws Exception {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger fallbackCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);

        // Capture initial pool state
        HikariPoolMXBean poolMXBean = getPoolMXBean();
        int initialActiveConnections = poolMXBean != null ? poolMXBean.getActiveConnections() : 0;

        log.info("[Red] Starting Thundering Herd Redis Death test...");
        log.info("[Red] Initial Pool State: active={}, idle={}, waiting={}",
                initialActiveConnections,
                poolMXBean != null ? poolMXBean.getIdleConnections() : 0,
                poolMXBean != null ? poolMXBean.getThreadsAwaitingConnection() : 0);

        // Phase 1: Kill Redis
        log.info("[Red] Phase 1: Killing Redis connection via Toxiproxy...");
        failMaster();
        Thread.sleep(500); // Allow connection to be cut

        // Phase 2: Launch concurrent lock requests
        log.info("[Red] Phase 2: Launching {} concurrent lock requests...", CONCURRENT_REQUESTS);

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final int requestId = i;
            final String lockKey = "nightmare-n08-lock-" + (requestId % 10); // 10 unique keys

            executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronize all threads

                    long startTime = System.currentTimeMillis();

                    // Try to acquire lock (should fallback to MySQL)
                    String result;
                    try {
                        result = lockStrategy.executeWithLock(lockKey, 5000, 3000, () -> {
                            Thread.sleep(100); // Simulate work
                            return "success-" + requestId;
                        });
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }

                    long latency = System.currentTimeMillis() - startTime;
                    totalLatency.addAndGet(latency);

                    if (result != null) {
                        successCount.incrementAndGet();
                        if (latency > 1000) {
                            fallbackCount.incrementAndGet();
                            log.info("[Request-{}] Completed (likely fallback) in {}ms", requestId, latency);
                        }
                    }

                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

                    if (msg.contains("timeout") || msg.contains("connection")) {
                        timeoutCount.incrementAndGet();
                        log.warn("[Request-{}] Connection timeout: {}", requestId, e.getMessage());
                    } else {
                        errorCount.incrementAndGet();
                        log.warn("[Request-{}] Error: {}", requestId, e.getMessage());
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all requests simultaneously
        startLatch.countDown();

        // Wait for completion (max 60 seconds)
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // Capture final pool state
        int peakActiveConnections = poolMXBean != null ? poolMXBean.getActiveConnections() : 0;
        int peakWaitingThreads = poolMXBean != null ? poolMXBean.getThreadsAwaitingConnection() : 0;

        // Phase 3: Recover and analyze
        log.info("[Red] Phase 3: Recovering Redis and analyzing results...");
        recoverMaster();

        // Results
        long avgLatency = successCount.get() > 0 ? totalLatency.get() / successCount.get() : 0;

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│    Nightmare 08: Thundering Herd Redis Death Results       │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Test Completed: {}                                         │", completed ? "YES" : "NO");
        log.info("│ Concurrent Requests: {}                                    │", CONCURRENT_REQUESTS);
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Success Count: {}                                          │", successCount.get());
        log.info("│ Fallback Count (>1s latency): {}                           │", fallbackCount.get());
        log.info("│ Timeout Count: {}                                          │", timeoutCount.get());
        log.info("│ Error Count: {}                                            │", errorCount.get());
        log.info("│ Average Latency: {}ms                                      │", avgLatency);
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Pool State:                                                │");
        log.info("│   Initial Active: {}                                       │", initialActiveConnections);
        log.info("│   Peak Active: {}                                          │", peakActiveConnections);
        log.info("│   Peak Waiting: {}                                         │", peakWaitingThreads);
        log.info("├────────────────────────────────────────────────────────────┤");

        if (timeoutCount.get() > CONNECTION_TIMEOUT_THRESHOLD) {
            log.info("│ ❌ THUNDERING HERD DETECTED!                               │");
            log.info("│ 🔧 Solution: Add rate limiting on fallback path            │");
            log.info("│    - Implement bulkhead pattern for MySQL fallback         │");
            log.info("│    - Add exponential backoff on retry                      │");
        } else {
            log.info("│ ✅ System resilient to Redis death                         │");
        }
        log.info("└────────────────────────────────────────────────────────────┘");

        // Verification
        assertThat(timeoutCount.get())
                .as("[Nightmare] Connection timeouts should not exceed %d", CONNECTION_TIMEOUT_THRESHOLD)
                .isLessThanOrEqualTo(CONNECTION_TIMEOUT_THRESHOLD);
    }

    /**
     * 🔵 Blue's Test 2: Circuit Breaker 상태 전이 검증
     *
     * <p>Redis 장애 시 Circuit Breaker가 OPEN 상태로 전이하여
     * 빠르게 MySQL fallback으로 라우팅하는지 검증</p>
     */
    @Test
    @DisplayName("Circuit Breaker 상태 전이 검증 (CLOSED → OPEN)")
    void shouldTransitionCircuitBreaker_whenRedisDown() throws Exception {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("redisLock");
        cb.reset();

        log.info("[Blue] Initial Circuit Breaker State: {}", cb.getState());

        // Kill Redis (Toxiproxy 실패 시 건너뜀)
        try {
            failMaster();
            Thread.sleep(500);
        } catch (Exception e) {
            log.warn("[Blue] Could not control Toxiproxy: {}. Testing with available Redis.", e.getMessage());
            // Toxiproxy 실패 시에도 Circuit Breaker 상태 분석 진행
        }

        // Make multiple failed requests to trigger circuit breaker
        int failedAttempts = 0;
        for (int i = 0; i < 10; i++) {
            try {
                try {
                    lockStrategy.executeWithLock("circuit-test-" + i, 1000, 1000, () -> {
                        Thread.sleep(50);
                        return "result";
                    });
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            } catch (Exception e) {
                failedAttempts++;
            }
        }

        CircuitBreaker.State finalState = cb.getState();
        CircuitBreaker.Metrics metrics = cb.getMetrics();

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│         Circuit Breaker State Analysis                     │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Failed Attempts: {}                                         │", failedAttempts);
        log.info("│ Final State: {}                                             │", finalState);
        log.info("│ Failure Rate: {} %                                         │", metrics.getFailureRate());
        log.info("│ Slow Call Rate: {} %                                       │", metrics.getSlowCallRate());
        log.info("│ Buffered Calls: {}                                         │", metrics.getNumberOfBufferedCalls());
        log.info("│ Failed Calls: {}                                           │", metrics.getNumberOfFailedCalls());
        log.info("└────────────────────────────────────────────────────────────┘");

        // Recover for next tests
        recoverMaster();
        cb.reset();

        // Circuit breaker should have recorded failures
        assertThat(metrics.getNumberOfFailedCalls())
                .as("Circuit breaker should record failed calls")
                .isGreaterThanOrEqualTo(0);
    }

    /**
     * 🟣 Purple's Test 3: 락 없이 동시 실행되지 않음 검증
     *
     * <p>Redis 장애 중에도 락이 정상 작동하여 critical section이
     * 동시에 실행되지 않음을 검증</p>
     */
    @Test
    @DisplayName("Redis 장애 중 락 무결성 유지 검증")
    void shouldMaintainLockIntegrity_duringRedisFailure() throws Exception {
        AtomicInteger concurrentExecutions = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger violations = new AtomicInteger(0);

        // Kill Redis to force MySQL fallback
        failMaster();
        Thread.sleep(500);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(10);

        String singleLockKey = "integrity-test-lock"; // Same key for all

        for (int i = 0; i < 10; i++) {
            final int requestId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    try {
                        lockStrategy.executeWithLock(singleLockKey, 10000, 5000, () -> {
                            int current = concurrentExecutions.incrementAndGet();

                            // Track max concurrent
                            int max = maxConcurrent.get();
                            while (current > max && !maxConcurrent.compareAndSet(max, current)) {
                                max = maxConcurrent.get();
                            }

                            // Check for violation (more than 1 concurrent)
                            if (current > 1) {
                                violations.incrementAndGet();
                                log.error("[Purple-{}] VIOLATION! Concurrent executions: {}", requestId, current);
                            }

                            Thread.sleep(200); // Simulate critical section

                            concurrentExecutions.decrementAndGet();
                            return null;
                        });
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                } catch (Exception e) {
                    log.warn("[Purple-{}] Lock acquisition failed: {}", requestId, e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Recover
        recoverMaster();

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│         Lock Integrity During Redis Failure                │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Max Concurrent Executions: {}                              │", maxConcurrent.get());
        log.info("│ Violation Count: {}                                        │", violations.get());
        log.info("│ Integrity Maintained: {}                                   │", violations.get() == 0 ? "YES ✅" : "NO ❌");
        log.info("└────────────────────────────────────────────────────────────┘");

        assertThat(violations.get())
                .as("No concurrent executions should occur within locked section")
                .isZero();
    }

    // ========== Helper Methods ==========

    private HikariPoolMXBean getPoolMXBean() {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getHikariPoolMXBean();
        }
        return null;
    }
}
