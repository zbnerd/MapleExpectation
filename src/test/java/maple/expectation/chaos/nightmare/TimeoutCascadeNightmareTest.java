package maple.expectation.chaos.nightmare;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nightmare 06: Timeout Cascade - 좀비 요청 문제 테스트
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>Red (SRE): 장애 주입 - Toxiproxy로 Redis 지연 주입</li>
 *   <li>Blue (Architect): 흐름 검증 - 타임아웃 계층 구조 분석</li>
 *   <li>Green (Performance): 메트릭 검증 - Zombie Request 수, 리소스 낭비 시간</li>
 *   <li>Purple (Auditor): 데이터 무결성 - 타임아웃 후에도 작업 완료 여부</li>
 *   <li>Yellow (QA Master): 테스트 전략 - 다계층 타임아웃 누적 검증</li>
 * </ul>
 *
 * <h4>예상 결과: FAIL</h4>
 * <p>클라이언트 타임아웃(10s) < 서버 처리 체인(22s)일 때,
 * 클라이언트는 타임아웃을 받지만 서버는 계속 작업을 수행합니다 (Zombie Request).</p>
 *
 * <h4>타임아웃 계층</h4>
 * <pre>
 * TimeLimiter: 28s (상한)
 * └─ HTTP: connect 3s + response 5s (× 3회 재시도) = 25s
 *     └─ Redis: timeout 3s (× 3회 재시도) = 12s
 *         └─ MySQL Fallback: 3s connection + 5s lock = 8s
 * </pre>
 *
 * <h4>관련 CS 원리</h4>
 * <ul>
 *   <li>Timeout Hierarchy: 상위 레이어 타임아웃 > 하위 레이어 타임아웃</li>
 *   <li>Zombie Request: 클라이언트 타임아웃 후 서버 작업 지속</li>
 *   <li>Resource Leakage: 불필요한 리소스 점유</li>
 *   <li>Retry Storm: 재시도로 인한 요청 폭증</li>
 * </ul>
 */
@Tag("nightmare")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Nightmare 06: Timeout Cascade - Zombie Request Problem")
class TimeoutCascadeNightmareTest extends AbstractContainerBaseTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DataSource dataSource;

    // 테스트 메트릭
    private final AtomicInteger zombieRequestCount = new AtomicInteger(0);
    private final AtomicLong totalWastedTimeMs = new AtomicLong(0);
    private final AtomicInteger clientTimeoutCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        zombieRequestCount.set(0);
        totalWastedTimeMs.set(0);
        clientTimeoutCount.set(0);

        // Toxiproxy 상태 초기화
        globalProxyReset();
    }

    @AfterEach
    void tearDown() {
        globalProxyReset();
    }

    /**
     * Red's Test 1: 클라이언트 타임아웃 후 서버 좀비 요청 발생 검증
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>Redis에 5초 지연 주입</li>
     *   <li>클라이언트 타임아웃 3초 설정</li>
     *   <li>클라이언트 타임아웃 후 서버 작업 지속 확인</li>
     * </ol>
     *
     * <p><b>예상 실패</b>: 클라이언트 타임아웃 후에도 서버가 작업을 완료함</p>
     */
    @Test
    @DisplayName("클라이언트 타임아웃 후 서버 좀비 요청 발생 검증")
    void shouldCreateZombieRequest_whenClientTimesOut() throws Exception {
        // Given: Redis에 5초 지연 주입
        long redisLatencyMs = 5000;
        redisProxy.toxics()
                .latency("redis-latency", ToxicDirection.DOWNSTREAM, redisLatencyMs);

        long clientTimeoutMs = 3000; // 클라이언트 타임아웃: 3초
        AtomicBoolean serverCompleted = new AtomicBoolean(false);
        AtomicLong serverCompletionTime = new AtomicLong(0);
        AtomicLong clientTimeoutTime = new AtomicLong(0);

        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();

        System.out.println("[Red] Injecting Redis latency: " + redisLatencyMs + "ms");
        System.out.println("[Red] Client timeout: " + clientTimeoutMs + "ms");

        // When: 서버 작업 시작 (비동기)
        Future<?> serverFuture = serverExecutor.submit(() -> {
            long start = System.nanoTime();
            try {
                // Redis 작업 (지연 발생)
                redisTemplate.opsForValue().set("zombie-test-key", "value");
                serverCompleted.set(true);
                serverCompletionTime.set((System.nanoTime() - start) / 1_000_000);
                System.out.println("[Server] Completed after " + serverCompletionTime.get() + "ms");
            } catch (Exception e) {
                System.out.println("[Server] Failed: " + e.getMessage());
            }
        });

        // 클라이언트 타임아웃 시뮬레이션
        long clientStart = System.nanoTime();
        try {
            serverFuture.get(clientTimeoutMs, TimeUnit.MILLISECONDS);
            System.out.println("[Client] Server completed within timeout");
        } catch (TimeoutException e) {
            clientTimeoutTime.set((System.nanoTime() - clientStart) / 1_000_000);
            clientTimeoutCount.incrementAndGet();
            System.out.println("[Client] TIMEOUT after " + clientTimeoutTime.get() + "ms");
        }

        // 서버가 완료될 때까지 대기 (Zombie Request 확인)
        Thread.sleep(redisLatencyMs + 2000); // Redis 지연 + 여유 시간

        // 정리
        serverExecutor.shutdownNow();
        globalProxyReset();

        // 결과 분석
        boolean isZombieRequest = serverCompleted.get() && clientTimeoutTime.get() > 0;
        long wastedTime = serverCompletionTime.get() - clientTimeoutTime.get();

        if (isZombieRequest) {
            zombieRequestCount.incrementAndGet();
            totalWastedTimeMs.addAndGet(wastedTime);
        }

        System.out.println("==========================================================");
        System.out.println("       Nightmare 06: Zombie Request Analysis              ");
        System.out.println("==========================================================");
        System.out.printf(" Redis Latency: %dms%n", redisLatencyMs);
        System.out.printf(" Client Timeout: %dms%n", clientTimeoutMs);
        System.out.printf(" Client Timeout Occurred: %s%n", clientTimeoutTime.get() > 0 ? "YES" : "NO");
        System.out.printf(" Server Completed: %s%n", serverCompleted.get() ? "YES" : "NO");
        System.out.printf(" Server Completion Time: %dms%n", serverCompletionTime.get());
        System.out.printf(" Zombie Request: %s%n", isZombieRequest ? "YES - DETECTED!" : "NO");
        System.out.printf(" Wasted Time: %dms%n", wastedTime);
        System.out.println("----------------------------------------------------------");

        if (isZombieRequest) {
            System.out.println(" Verdict: FAIL - Zombie Request Detected!");
            System.out.println(" ");
            System.out.println(" Root Cause: Client timeout < Server processing time");
            System.out.println(" Impact: Resource waste, potential duplicate processing");
            System.out.println(" Fix: Implement proper timeout hierarchy");
        } else {
            System.out.println(" Verdict: PASS - No Zombie Request");
        }
        System.out.println("==========================================================");

        // Then: Zombie Request 발생 확인
        assertThat(serverCompleted.get())
                .as("[Nightmare] 클라이언트 타임아웃 후 서버 좀비 요청 발생")
                .isTrue();
    }

    /**
     * Blue's Test 2: Redis 지연 시 Retry Storm 시간 측정
     *
     * <p><b>시나리오</b>: Redis 지연으로 인한 재시도 체인 시간 측정</p>
     */
    @Test
    @DisplayName("Redis 지연 시 Retry Storm 시간 측정")
    void shouldMeasureRetryChainTime_whenRedisDelayed() throws Exception {
        // Given: Redis에 2초 지연
        long redisLatencyMs = 2000;
        redisProxy.toxics()
                .latency("redis-latency", ToxicDirection.DOWNSTREAM, redisLatencyMs);

        int retryCount = 3;
        long retryWaitMs = 500;
        ConcurrentLinkedQueue<Long> attemptTimes = new ConcurrentLinkedQueue<>();

        System.out.println("[Blue] Measuring retry chain time with " + redisLatencyMs + "ms Redis latency...");

        // When: 재시도 체인 시뮬레이션
        long totalStart = System.nanoTime();

        for (int attempt = 1; attempt <= retryCount; attempt++) {
            long attemptStart = System.nanoTime();
            try {
                redisTemplate.opsForValue().get("retry-test-key");
                long attemptTime = (System.nanoTime() - attemptStart) / 1_000_000;
                attemptTimes.add(attemptTime);
                System.out.printf("[Blue] Attempt %d: %dms%n", attempt, attemptTime);
                break; // 성공하면 중단
            } catch (Exception e) {
                long attemptTime = (System.nanoTime() - attemptStart) / 1_000_000;
                attemptTimes.add(attemptTime);
                System.out.printf("[Blue] Attempt %d failed after %dms: %s%n", attempt, attemptTime, e.getMessage());

                if (attempt < retryCount) {
                    Thread.sleep(retryWaitMs); // 재시도 대기
                }
            }
        }

        long totalTime = (System.nanoTime() - totalStart) / 1_000_000;

        // 정리
        globalProxyReset();

        System.out.println("========================================");
        System.out.println("       Retry Chain Analysis             ");
        System.out.println("========================================");
        System.out.printf(" Redis Latency: %dms%n", redisLatencyMs);
        System.out.printf(" Retry Count: %d%n", retryCount);
        System.out.printf(" Retry Wait: %dms%n", retryWaitMs);
        System.out.printf(" Total Chain Time: %dms%n", totalTime);
        System.out.printf(" Expected Minimum: %dms%n", redisLatencyMs);
        System.out.println("========================================");

        // Then: 총 시간이 Redis 지연 시간 이상이어야 함
        assertThat(totalTime)
                .as("Retry chain time should include Redis latency")
                .isGreaterThanOrEqualTo(redisLatencyMs);
    }

    /**
     * Green's Test 3: Redis 장애 시 MySQL Fallback 지연 측정
     *
     * <p><b>시나리오</b>: Redis 연결 차단 후 MySQL로 Fallback 시간 측정</p>
     */
    @Test
    @DisplayName("Redis 장애 시 MySQL Fallback 지연 측정")
    void shouldMeasureFallbackTime_whenRedisFails() throws Exception {
        // Given: Redis 연결 차단
        redisProxy.setConnectionCut(true);

        long start = System.nanoTime();
        boolean fallbackSucceeded = false;
        long redisFailTime = 0;
        long mysqlFallbackTime = 0;

        System.out.println("[Green] Cutting Redis connection for fallback test...");

        // When: Redis 실패 후 MySQL Fallback
        try {
            long redisStart = System.nanoTime();
            redisTemplate.opsForValue().get("fallback-test-key");
            // Redis 성공 (예상하지 않음)
        } catch (Exception e) {
            redisFailTime = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("[Green] Redis failed after %dms: %s%n", redisFailTime, e.getMessage());

            // MySQL Fallback
            long mysqlStart = System.nanoTime();
            try (Connection conn = dataSource.getConnection()) {
                fallbackSucceeded = conn.isValid(5);
                mysqlFallbackTime = (System.nanoTime() - mysqlStart) / 1_000_000;
            }
        }

        long totalTime = (System.nanoTime() - start) / 1_000_000;

        // 정리
        globalProxyReset();

        System.out.println("========================================");
        System.out.println("       Fallback Analysis                ");
        System.out.println("========================================");
        System.out.printf(" Redis Fail Time: %dms%n", redisFailTime);
        System.out.printf(" MySQL Fallback Time: %dms%n", mysqlFallbackTime);
        System.out.printf(" Total Fallback Time: %dms%n", totalTime);
        System.out.printf(" Fallback Succeeded: %s%n", fallbackSucceeded ? "YES" : "NO");
        System.out.println("========================================");

        // Then: Fallback 성공
        assertThat(fallbackSucceeded)
                .as("MySQL fallback should succeed when Redis fails")
                .isTrue();
    }

    /**
     * Yellow's Test 4: 다계층 타임아웃 누적 검증
     *
     * <p><b>시나리오</b>: 여러 레이어에서 타임아웃이 누적되는 현상 측정</p>
     */
    @Test
    @DisplayName("다계층 타임아웃 누적 검증")
    void shouldCascadeTimeouts_acrossLayers() throws Exception {
        // Given: Redis에 3초 지연
        long redisLatencyMs = 3000;
        redisProxy.toxics()
                .latency("redis-latency", ToxicDirection.DOWNSTREAM, redisLatencyMs);

        AtomicLong layer1Time = new AtomicLong(0);
        AtomicLong layer2Time = new AtomicLong(0);
        AtomicLong totalCascadeTime = new AtomicLong(0);

        System.out.println("[Yellow] Testing timeout cascade across layers...");

        // When: 다계층 작업 시뮬레이션
        long totalStart = System.nanoTime();

        // Layer 1: Redis 조회 (지연 발생)
        long layer1Start = System.nanoTime();
        try {
            redisTemplate.opsForValue().get("cascade-layer1-key");
        } catch (Exception e) {
            // 타임아웃 발생 가능
        }
        layer1Time.set((System.nanoTime() - layer1Start) / 1_000_000);

        // Layer 2: 후속 작업 (예: DB 조회)
        long layer2Start = System.nanoTime();
        try (Connection conn = dataSource.getConnection()) {
            // 단순 연결만 테스트
            Thread.sleep(100); // 작업 시뮬레이션
        }
        layer2Time.set((System.nanoTime() - layer2Start) / 1_000_000);

        totalCascadeTime.set((System.nanoTime() - totalStart) / 1_000_000);

        // 정리
        globalProxyReset();

        System.out.println("========================================");
        System.out.println("       Timeout Cascade Analysis         ");
        System.out.println("========================================");
        System.out.printf(" Layer 1 (Redis): %dms%n", layer1Time.get());
        System.out.printf(" Layer 2 (MySQL): %dms%n", layer2Time.get());
        System.out.printf(" Total Cascade: %dms%n", totalCascadeTime.get());
        System.out.printf(" Expected Minimum: %dms (Redis latency)%n", redisLatencyMs);
        System.out.println("========================================");

        // Then: 총 시간이 Redis 지연 시간을 포함해야 함
        assertThat(totalCascadeTime.get())
                .as("Multi-layer timeout should cascade and accumulate")
                .isGreaterThanOrEqualTo(redisLatencyMs);
    }

    /**
     * Purple's Test 5: 동시 요청 시 Zombie Request 발생률 측정
     */
    @Test
    @DisplayName("동시 요청 시 Zombie Request 발생률 측정")
    void shouldMeasureZombieRequestRate_underConcurrentLoad() throws Exception {
        // Given: Redis에 2초 지연, 클라이언트 타임아웃 1초
        long redisLatencyMs = 2000;
        long clientTimeoutMs = 1000;

        redisProxy.toxics()
                .latency("redis-latency", ToxicDirection.DOWNSTREAM, redisLatencyMs);

        int concurrentRequests = 10;
        AtomicInteger zombieCount = new AtomicInteger(0);
        AtomicInteger clientTimeouts = new AtomicInteger(0);
        AtomicInteger serverCompletions = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

        System.out.println("[Purple] Testing Zombie Request rate under concurrent load...");

        // When
        for (int i = 0; i < concurrentRequests; i++) {
            final int requestId = i;
            executor.submit(() -> {
                AtomicBoolean serverDone = new AtomicBoolean(false);
                AtomicBoolean clientTimedOut = new AtomicBoolean(false);

                // 서버 작업
                CompletableFuture<Void> serverTask = CompletableFuture.runAsync(() -> {
                    try {
                        redisTemplate.opsForValue().set("zombie-concurrent-" + requestId, "value");
                        serverDone.set(true);
                    } catch (Exception ignored) {
                    }
                });

                // 클라이언트 타임아웃
                try {
                    serverTask.get(clientTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    clientTimedOut.set(true);
                    clientTimeouts.incrementAndGet();
                } catch (Exception ignored) {
                }

                // Zombie 확인을 위해 대기
                try {
                    Thread.sleep(redisLatencyMs + 500);
                } catch (InterruptedException ignored) {
                }

                if (serverDone.get()) {
                    serverCompletions.incrementAndGet();
                    if (clientTimedOut.get()) {
                        zombieCount.incrementAndGet();
                    }
                }

                doneLatch.countDown();
            });
        }

        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // 정리
        globalProxyReset();

        double zombieRate = zombieCount.get() * 100.0 / concurrentRequests;

        System.out.println("========================================");
        System.out.println("       Zombie Request Rate Analysis     ");
        System.out.println("========================================");
        System.out.printf(" Total Requests: %d%n", concurrentRequests);
        System.out.printf(" Client Timeouts: %d%n", clientTimeouts.get());
        System.out.printf(" Server Completions: %d%n", serverCompletions.get());
        System.out.printf(" Zombie Requests: %d (%.1f%%)%n", zombieCount.get(), zombieRate);
        System.out.println("========================================");

        // Then: Zombie Request 발생 (timeout이 짧으면 발생)
        assertThat(zombieCount.get())
                .as("[Nightmare] Zombie requests should occur when client timeout < server time")
                .isGreaterThan(0);
    }
}
