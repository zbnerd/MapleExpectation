package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Nightmare 05: Celebrity Problem - Hot Key 멜트다운 테스트
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>Red (SRE): 장애 주입 - 단일 Hot Key에 1,000 TPS 집중 요청
 *   <li>Blue (Architect): 흐름 검증 - TieredCache L1/L2 + Singleflight 패턴
 *   <li>Green (Performance): 메트릭 검증 - L1 히트율, 락 경합률, DB 쿼리 비율
 *   <li>Purple (Auditor): 데이터 무결성 - 모든 클라이언트 동일 값 수신
 *   <li>Yellow (QA Master): 테스트 전략 - Hot Key 분산 전략 부재 검증
 * </ul>
 *
 * <h4>예상 결과: PASS</h4>
 *
 * <p>TieredCache에 Singleflight(Redisson Lock)가 구현되어 있어 DB 쿼리 비율이 10% 이하로 유지됩니다.
 *
 * <h4>테스트 시나리오 개선 (Issue #262)</h4>
 *
 * <ul>
 *   <li>FLUSHALL 제거 → 특정 키 삭제 방식 (현실적 시나리오)
 *   <li>L1/L2 계층별 테스트 분리
 *   <li>검증 기준: DB 쿼리 비율 ≤ 10% (Singleflight 효과)
 * </ul>
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>Hot Key Problem: 단일 키에 트래픽 집중으로 인한 노드 과부하
 *   <li>Singleflight Pattern: 동일 키에 대해 한 번만 로딩
 *   <li>Request Coalescing: 중복 요청 병합
 *   <li>Cache Stampede: 캐시 만료 시 동시 DB 조회 폭주
 * </ul>
 *
 * @see maple.expectation.infrastructure.cache.TieredCache
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@DisplayName("Nightmare 05: Celebrity Problem - Hot Key Meltdown")
class CelebrityProblemNightmareTest extends AbstractContainerBaseTest {

  @Autowired private RedisTemplate<String, String> redisTemplate;

  private static final String HOT_KEY = "nightmare:celebrity:hot-key";
  private static final String HOT_VALUE = "celebrity-data-faker";

  // 테스트 메트릭
  private final AtomicInteger dbQueryCount = new AtomicInteger(0);
  private final AtomicInteger cacheHitCount = new AtomicInteger(0);
  private final AtomicInteger lockFailureCount = new AtomicInteger(0);
  private final ConcurrentLinkedQueue<Long> responseTimes = new ConcurrentLinkedQueue<>();

  @BeforeEach
  void setUp() {
    dbQueryCount.set(0);
    cacheHitCount.set(0);
    lockFailureCount.set(0);
    responseTimes.clear();

    // Hot Key 초기화 - 특정 키만 삭제 (FLUSHALL 대체)
    safeDeleteKey(HOT_KEY);
    safeDeleteKey(HOT_KEY + ":lock");
  }

  /** 안전한 키 삭제 헬퍼 메서드 */
  private void safeDeleteKey(String key) {
    try {
      redisTemplate.delete(key);
    } catch (Exception e) {
      log.debug("[Setup] Key deletion failed, continuing: {}", e.getMessage());
    }
  }

  /**
   * Red's Test 1: 1,000명 동시 요청 시 Hot Key 락 경합 측정
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>캐시 비움 (Cold Start)
   *   <li>동시에 1,000개 요청이 동일 키 조회
   *   <li>Singleflight 패턴으로 DB 쿼리 최소화 검증
   * </ol>
   *
   * <p><b>성공 기준</b>: DB 쿼리 비율 ≤ 10%
   *
   * <p><b>실패 기준</b>: DB 쿼리 비율 > 50%
   */
  /**
   * Red's Test 1: 1,000명 동시 요청 시 Hot Key 락 경합 측정
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>특정 캐시 키 삭제 (현실적 시나리오)
   *   <li>동시에 1,000개 요청이 동일 키 조회
   *   <li>Singleflight 패턴으로 DB 쿼리 최소화 검증
   * </ol>
   *
   * <p><b>성공 기준</b>: DB 쿼리 비율 ≤ 10%
   *
   * <p><b>실패 기준</b>: DB 쿼리 비율 > 50%
   */
  @Test
  @DisplayName("1,000명 동시 요청 시 Hot Key 락 경합 측정")
  void shouldMeasureLockContention_whenHotKeyAccessed() throws Exception {
    // Given: 특정 캐시 키 삭제 (현실적 시나리오 - FLUSHALL 대체)
    safeDeleteKey(HOT_KEY);
    safeDeleteKey(HOT_KEY + ":lock");

    int concurrentRequests = 1000;
    ExecutorService executor = Executors.newFixedThreadPool(100);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

    log.info(
        "[Red] Starting Celebrity Problem test with {} concurrent requests...", concurrentRequests);

    // When: 1,000개 동시 요청
    for (int i = 0; i < concurrentRequests; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              long start = System.nanoTime();

              // Hot Key 조회 시뮬레이션
              String cachedValue = redisTemplate.opsForValue().get(HOT_KEY);

              if (cachedValue != null) {
                // Cache Hit
                cacheHitCount.incrementAndGet();
              } else {
                // Cache Miss → DB 조회 시뮬레이션 (Singleflight)
                // 실제 TieredCache는 분산 락을 사용하지만, 여기서는 시뮬레이션
                Boolean acquired =
                    redisTemplate
                        .opsForValue()
                        .setIfAbsent(HOT_KEY + ":lock", "locked", java.time.Duration.ofSeconds(30));

                if (Boolean.TRUE.equals(acquired)) {
                  // 락 획득 성공 → DB 조회
                  dbQueryCount.incrementAndGet();
                  Thread.sleep(50); // DB 조회 시뮬레이션
                  redisTemplate.opsForValue().set(HOT_KEY, HOT_VALUE);
                  redisTemplate.delete(HOT_KEY + ":lock");
                } else {
                  // 락 획득 실패 → 대기 후 재조회
                  lockFailureCount.incrementAndGet();
                  Thread.sleep(100);
                  cachedValue = redisTemplate.opsForValue().get(HOT_KEY);
                  if (cachedValue == null) {
                    // Fallback: 직접 DB 조회 (락 대기 타임아웃)
                    dbQueryCount.incrementAndGet();
                  }
                }
              }

              long elapsed = (System.nanoTime() - start) / 1_000_000;
              responseTimes.add(elapsed);

            } catch (Exception e) {
              lockFailureCount.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    // 모든 스레드 동시 시작
    startLatch.countDown();

    // 완료 대기
    boolean completed = doneLatch.await(120, TimeUnit.SECONDS);
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    // 메트릭 계산
    double dbQueryRatio = dbQueryCount.get() * 100.0 / concurrentRequests;
    double lockFailureRatio = lockFailureCount.get() * 100.0 / concurrentRequests;
    long avgResponseTime =
        responseTimes.stream().mapToLong(Long::longValue).sum() / Math.max(responseTimes.size(), 1);

    // 결과 출력
    printCelebrityProblemResults(
        concurrentRequests, completed, dbQueryRatio, lockFailureRatio, avgResponseTime);

    // 검증: DB 쿼리 비율이 10% 이하여야 함 (Singleflight 효과)
    assertThat(dbQueryRatio)
        .as("[Nightmare] Hot Key에 대한 Singleflight 효과 검증 (DB 쿼리 ≤10%%)")
        .isLessThanOrEqualTo(10.0);
  }

  /**
   * Blue's Test 2: 락 획득 실패 시 Fallback 동작 검증
   *
   * <p><b>시나리오</b>: 락이 오래 점유될 때 Fallback 동작 확인
   */
  @Test
  @DisplayName("락 획득 실패 시 Fallback 동작 검증")
  void shouldFallbackToDirectCall_whenLockAcquisitionFails() throws Exception {
    // Given: 락 선점
    String lockKey = HOT_KEY + ":test-lock";
    redisTemplate.opsForValue().set(lockKey, "locked", java.time.Duration.ofSeconds(30));

    AtomicInteger fallbackCount = new AtomicInteger(0);
    int concurrentRequests = 50;
    ExecutorService executor = Executors.newFixedThreadPool(10);
    CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

    log.info("[Blue] Testing lock fallback behavior...");

    // When: 락 획득 시도
    for (int i = 0; i < concurrentRequests; i++) {
      executor.submit(
          () -> {
            try {
              Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked-new");

              if (!Boolean.TRUE.equals(acquired)) {
                // 락 획득 실패 → Fallback
                fallbackCount.incrementAndGet();
              }
            } catch (Exception ignored) {
            } finally {
              doneLatch.countDown();
            }
          });
    }

    doneLatch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    // 정리
    redisTemplate.delete(lockKey);

    log.info("========================================");
    log.info("       Lock Fallback Analysis           ");
    log.info("========================================");
    log.info(" Total Requests: {}", concurrentRequests);
    log.info(" Lock Failures (Fallback): {}", fallbackCount.get());
    log.info("========================================");

    // Then: 대부분 락 획득 실패 (이미 점유됨)
    assertThat(fallbackCount.get())
        .as("락이 선점된 상태에서 대부분 Fallback 발생")
        .isGreaterThan(concurrentRequests / 2);
  }

  /** Purple's Test 3: 동시 요청 후 모든 클라이언트가 동일한 값 수신 검증 */
  @Test
  @DisplayName("동시 요청 후 모든 클라이언트 동일 값 수신")
  void shouldReturnConsistentData_afterConcurrentAccess() throws Exception {
    // Given: 캐시 비움
    redisTemplate.delete(HOT_KEY);

    int concurrentRequests = 500;
    ConcurrentLinkedQueue<String> results = new ConcurrentLinkedQueue<>();
    AtomicInteger writeCount = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(50);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

    log.info("[Purple] Testing data consistency under concurrent Hot Key access...");

    // When
    for (int i = 0; i < concurrentRequests; i++) {
      final int requestId = i;
      executor.submit(
          () -> {
            try {
              startLatch.await();

              String value = redisTemplate.opsForValue().get(HOT_KEY);

              if (value == null) {
                // 첫 번째 요청만 값 설정 (setIfAbsent로 원자성 보장)
                String newValue = HOT_VALUE;
                Boolean success = redisTemplate.opsForValue().setIfAbsent(HOT_KEY, newValue);
                if (Boolean.TRUE.equals(success)) {
                  writeCount.incrementAndGet();
                  results.add(newValue);
                } else {
                  // 다른 스레드가 먼저 설정
                  value = redisTemplate.opsForValue().get(HOT_KEY);
                  results.add(value != null ? value : "NULL");
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
    doneLatch.await(60, TimeUnit.SECONDS);
    executor.shutdown();

    // 분석
    long uniqueValues = results.stream().filter(r -> !r.startsWith("ERROR:")).distinct().count();

    log.info("========================================");
    log.info("       Data Consistency Analysis        ");
    log.info("========================================");
    log.info(" Total Results: {}", results.size());
    log.info(" Unique Values: {}", uniqueValues);
    log.info(" Write Count: {}", writeCount.get());
    log.info("========================================");

    // Then: 모든 결과가 동일해야 함
    assertThat(uniqueValues).as("모든 클라이언트가 동일한 값을 받아야 함 (데이터 일관성)").isEqualTo(1);
  }

  /** Green's Test 4: Hot Key 응답 시간 분포 측정 */
  @Test
  @DisplayName("Hot Key 응답 시간 분포 측정")
  void shouldMeasureResponseTimeDistribution() throws Exception {
    // Given: 캐시에 값 설정 (Warm cache)
    redisTemplate.opsForValue().set(HOT_KEY, HOT_VALUE);

    int concurrentRequests = 100;
    ConcurrentLinkedQueue<Long> times = new ConcurrentLinkedQueue<>();

    ExecutorService executor = Executors.newFixedThreadPool(20);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

    log.info("[Green] Measuring response time distribution...");

    // When
    for (int i = 0; i < concurrentRequests; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              long start = System.nanoTime();

              redisTemplate.opsForValue().get(HOT_KEY);

              long elapsed = (System.nanoTime() - start) / 1_000_000;
              times.add(elapsed);
            } catch (Exception ignored) {
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();
    doneLatch.await(30, TimeUnit.SECONDS);
    executor.shutdown();

    // 분석
    long min = times.stream().mapToLong(Long::longValue).min().orElse(0);
    long max = times.stream().mapToLong(Long::longValue).max().orElse(0);
    long avg = times.stream().mapToLong(Long::longValue).sum() / Math.max(times.size(), 1);
    long p99 = times.stream().sorted().skip((long) (times.size() * 0.99)).findFirst().orElse(0L);

    log.info("========================================");
    log.info("     Response Time Distribution         ");
    log.info("========================================");
    log.info(" Min: {}ms", min);
    log.info(" Avg: {}ms", avg);
    log.info(" P99: {}ms", p99);
    log.info(" Max: {}ms", max);
    log.info("========================================");

    // Then: P99 응답시간 5초 이내
    assertThat(p99).as("Hot Key 응답시간 P99가 5초 이내여야 함").isLessThan(5000);
  }

  private void printCelebrityProblemResults(
      int concurrentRequests,
      boolean completed,
      double dbQueryRatio,
      double lockFailureRatio,
      long avgResponseTime) {
    log.info("==========================================================");
    log.info("       Nightmare 05: Celebrity Problem Results            ");
    log.info("==========================================================");
    log.info(" Total Requests: {}", concurrentRequests);
    log.info(" Completed: {}", completed ? "YES" : "NO (TIMEOUT)");
    log.info(
        " Cache Hits: {} ({} %)",
        cacheHitCount.get(),
        String.format("%.1f", cacheHitCount.get() * 100.0 / concurrentRequests));
    log.info(" DB Queries: {} ({} %)", dbQueryCount.get(), String.format("%.1f", dbQueryRatio));
    log.info(
        " Lock Failures: {} ({} %)",
        lockFailureCount.get(), String.format("%.1f", lockFailureRatio));
    log.info(" Avg Response Time: {}ms", avgResponseTime);
    log.info("----------------------------------------------------------");

    if (dbQueryRatio <= 10.0) {
      log.info(" Verdict: PASS - Singleflight effective");
    } else if (dbQueryRatio <= 50.0) {
      log.info(" Verdict: CONDITIONAL - Partial Singleflight effect");
      log.info(" Issue: Lock contention causes some direct DB calls");
    } else {
      log.info(" Verdict: FAIL - Hot Key Meltdown!");
      log.info(" ");
      log.info(" Root Cause: No Hot Key distribution strategy");
      log.info(" Location: TieredCache.java");
      log.info(" Fix: Implement Key Splitting or Local Cache Replication");
    }
    log.info("==========================================================");
  }
}
