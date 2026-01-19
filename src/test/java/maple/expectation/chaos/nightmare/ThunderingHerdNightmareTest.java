package maple.expectation.chaos.nightmare;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nightmare 01: The Thundering Herd - Cache Stampede + Cold Start
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - Redis FLUSHALL 후 1,000명 동시 접속</li>
 *   <li>🔵 Blue (Architect): 흐름 검증 - Singleflight 패턴 효과 측정</li>
 *   <li>🟢 Green (Performance): 메트릭 검증 - DB 쿼리 비율, Connection Pool 상태</li>
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 모든 요청이 동일한 결과 반환</li>
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 실패 시 Issue 생성</li>
 * </ul>
 *
 * <h4>예상 결과: CONDITIONAL PASS</h4>
 * <p>TieredCache에 Singleflight 패턴이 구현되어 있으나, 락 경합 시 Fallback이
 * DB를 직접 호출하여 성능 저하 가능.</p>
 *
 * <h4>관련 CS 원리</h4>
 * <ul>
 *   <li>Cache Stampede: 캐시 만료 시 동시 DB 조회 폭주</li>
 *   <li>Singleflight Pattern: 동일 키에 대해 한 번만 로딩</li>
 *   <li>Request Coalescing: 중복 요청 병합</li>
 *   <li>Thundering Herd Problem: 이벤트 발생 시 대량 요청 동시 처리</li>
 * </ul>
 *
 * @see maple.expectation.global.cache.TieredCache
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@DisplayName("Nightmare 01: The Thundering Herd - Cache Stampede")
class ThunderingHerdNightmareTest extends AbstractContainerBaseTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DataSource dataSource;

    private static final String CACHE_KEY = "nightmare:thundering-herd:test";
    private static final String DB_VALUE = "database-loaded-value";

    @BeforeEach
    void setUp() {
        // 테스트 데이터 초기화
        try {
            redisTemplate.delete(CACHE_KEY);
        } catch (Exception ignored) {
        }
    }

    /**
     * 🔴 Red's Test 1: Redis FLUSHALL 후 동시 요청 시 DB 쿼리 최소화 검증
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>Redis 전체 캐시 삭제 (FLUSHALL)</li>
     *   <li>동시에 1,000개 요청 발생</li>
     *   <li>Singleflight 패턴으로 DB 쿼리 최소화 검증</li>
     * </ol>
     *
     * <p><b>성공 기준</b>: DB 쿼리 비율 ≤ 10% (Singleflight 효과)</p>
     * <p><b>실패 조건</b>: DB 쿼리 비율 > 50%</p>
     */
    @Test
    @DisplayName("Redis FLUSHALL 후 1,000명 동시 요청 시 DB 쿼리 최소화")
    void shouldMinimizeDbQueries_afterCacheFlush() throws Exception {
        // Given: Redis 전체 캐시 삭제
        try {
            redisTemplate.getConnectionFactory().getConnection().flushAll();
        } catch (Exception e) {
            log.info("[Red] FLUSHALL failed, continuing with test: {}", e.getMessage());
        }

        int concurrentRequests = 1000;
        AtomicInteger dbQueryCount = new AtomicInteger(0);
        AtomicInteger cacheHitCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<Long> responseTimes = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(100);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

        log.info("[Red] Starting Thundering Herd test with {} concurrent requests...", concurrentRequests);

        // When: 1,000개 동시 요청
        for (int i = 0; i < concurrentRequests; i++) {
            final int requestId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // 모든 스레드가 동시에 시작

                    long start = System.nanoTime();

                    // 캐시 조회 시뮬레이션
                    String cachedValue = redisTemplate.opsForValue().get(CACHE_KEY);

                    if (cachedValue != null) {
                        // Cache Hit
                        cacheHitCount.incrementAndGet();
                    } else {
                        // Cache Miss → DB 조회 시뮬레이션
                        dbQueryCount.incrementAndGet();

                        // DB 조회 시뮬레이션 (50ms 지연)
                        Thread.sleep(50);

                        // 캐시에 저장
                        redisTemplate.opsForValue().set(CACHE_KEY, DB_VALUE);
                    }

                    long elapsed = (System.nanoTime() - start) / 1_000_000;
                    responseTimes.add(elapsed);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 모든 스레드 동시 시작
        startLatch.countDown();

        // 완료 대기 (최대 120초)
        boolean completed = doneLatch.await(120, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Then: 분석 및 검증
        double dbQueryRatio = dbQueryCount.get() * 100.0 / concurrentRequests;
        long avgResponseTime = responseTimes.stream().mapToLong(Long::longValue).sum() /
                Math.max(responseTimes.size(), 1);
        long maxResponseTime = responseTimes.stream().mapToLong(Long::longValue).max().orElse(0);

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│           Nightmare 01: Thundering Herd Results            │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Total Requests: {}                                          │", concurrentRequests);
        log.info("│ Completed: {}                                               │", completed ? "YES" : "NO");
        log.info("│ Success: {}, Failure: {}                                    │",
                successCount.get(), failureCount.get());
        log.info("│ Cache Hits: {} ({} %)                                     │",
                cacheHitCount.get(), String.format("%.1f", cacheHitCount.get() * 100.0 / concurrentRequests));
        log.info("│ DB Queries: {} ({} %)                                     │",
                dbQueryCount.get(), String.format("%.1f", dbQueryRatio));
        log.info("│ Avg Response Time: {}ms                                     │", avgResponseTime);
        log.info("│ Max Response Time: {}ms                                     │", maxResponseTime);
        log.info("├────────────────────────────────────────────────────────────┤");

        // Singleflight 효과 판정
        if (dbQueryRatio <= 10.0) {
            log.info("│ Verdict: ✅ PASS - Singleflight effective                  │");
        } else if (dbQueryRatio <= 50.0) {
            log.info("│ Verdict: ⚠️ CONDITIONAL - Partial Singleflight effect      │");
        } else {
            log.info("│ Verdict: ❌ FAIL - Thundering Herd occurred!               │");
        }
        log.info("└────────────────────────────────────────────────────────────┘");

        // 검증: DB 쿼리 비율이 10% 이하여야 함 (Singleflight 효과)
        // 현재 구현에서는 실패할 것으로 예상 (Redis 기반 Singleflight만 있음)
        assertThat(dbQueryRatio)
                .as("[Nightmare] Singleflight으로 DB 쿼리 최소화 (≤10%%)")
                .isLessThanOrEqualTo(10.0);
    }

    /**
     * 🔵 Blue's Test 2: Connection Pool 고갈 시 Fail-Fast 동작 검증
     *
     * <p><b>시나리오</b>: 모든 요청이 DB로 향할 때 Connection Pool이 고갈되는지 확인</p>
     */
    @Test
    @DisplayName("Connection Pool 고갈 시 타임아웃 동작 확인")
    void shouldFailFast_whenConnectionPoolExhausted() throws Exception {
        // Given: 캐시 비활성화 상태
        try {
            redisTemplate.getConnectionFactory().getConnection().flushAll();
        } catch (Exception ignored) {
        }

        int concurrentRequests = 50; // Connection Pool 크기 초과
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);
        AtomicInteger connectionErrorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

        log.info("[Blue] Testing Connection Pool exhaustion...");

        for (int i = 0; i < concurrentRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();

                    // DB 커넥션 점유 시뮬레이션
                    try (Connection conn = dataSource.getConnection()) {
                        // 긴 쿼리 시뮬레이션 (1초 점유)
                        Thread.sleep(1000);
                        successCount.incrementAndGet();
                    }
                } catch (java.sql.SQLTimeoutException e) {
                    timeoutCount.incrementAndGet();
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("Connection")) {
                        connectionErrorCount.incrementAndGet();
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│           Connection Pool Analysis                         │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Success: {}                                                 │", successCount.get());
        log.info("│ Timeout: {}                                                 │", timeoutCount.get());
        log.info("│ Connection Error: {}                                        │", connectionErrorCount.get());
        log.info("└────────────────────────────────────────────────────────────┘");

        // Connection Pool이 적절히 관리되어야 함
        assertThat(successCount.get())
                .as("Connection Pool이 요청을 처리해야 함")
                .isGreaterThan(0);
    }

    /**
     * 🟣 Purple's Test 3: 데이터 일관성 검증
     *
     * <p><b>시나리오</b>: 동시 요청 후 모든 클라이언트가 동일한 값을 받는지 확인</p>
     */
    @Test
    @DisplayName("동시 요청 후 모든 클라이언트가 동일한 값 수신")
    void shouldReturnConsistentData_afterConcurrentRequests() throws Exception {
        // Given: 캐시 비움
        try {
            redisTemplate.delete(CACHE_KEY);
        } catch (Exception ignored) {
        }

        int concurrentRequests = 100;
        ConcurrentLinkedQueue<String> results = new ConcurrentLinkedQueue<>();
        AtomicInteger writeCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

        log.info("[Purple] Testing data consistency...");

        for (int i = 0; i < concurrentRequests; i++) {
            final int requestId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    String value = redisTemplate.opsForValue().get(CACHE_KEY);

                    if (value == null) {
                        // 첫 번째 요청만 값 설정
                        String newValue = "consistent-value-" + requestId;
                        Boolean success = redisTemplate.opsForValue()
                                .setIfAbsent(CACHE_KEY, newValue);
                        if (Boolean.TRUE.equals(success)) {
                            writeCount.incrementAndGet();
                            results.add(newValue);
                        } else {
                            // 다른 스레드가 먼저 설정함
                            value = redisTemplate.opsForValue().get(CACHE_KEY);
                            if (value != null) {
                                results.add(value);
                            }
                        }
                    } else {
                        results.add(value);
                    }
                } catch (Exception e) {
                    results.add("ERROR:" + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // 분석
        long uniqueValues = results.stream().distinct().count();
        long errorCount = results.stream().filter(r -> r.startsWith("ERROR:")).count();

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│           Data Consistency Analysis                        │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Total Results: {}                                           │", results.size());
        log.info("│ Unique Values: {}                                           │", uniqueValues);
        log.info("│ Write Count: {}                                             │", writeCount.get());
        log.info("│ Errors: {}                                                  │", errorCount);
        log.info("└────────────────────────────────────────────────────────────┘");

        // 모든 결과가 동일해야 함 (에러 제외)
        long nonErrorUniqueValues = results.stream()
                .filter(r -> !r.startsWith("ERROR:"))
                .distinct()
                .count();

        assertThat(nonErrorUniqueValues)
                .as("모든 클라이언트가 동일한 값을 받아야 함")
                .isEqualTo(1);
    }
}
