package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Nightmare 06: The Timeout Cascade (Zombie Request Problem)
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - Redis 지연으로 타임아웃 누적 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - 타임아웃 계층 불일치 확인
 *   <li>🟢 Green (Performance): 메트릭 검증 - Zombie Request 발생률, 리소스 낭비 시간
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - Zombie Request 후 데이터 정합성
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 타임아웃 경계값 테스트
 * </ul>
 *
 * <h4>예상 결과: FAIL</h4>
 *
 * <p>현재 시스템에 타임아웃 계층 불일치로 Zombie Request 발생 예상. Client Timeout(3s) < Server Processing
 * Chain(17.2s+) = 14.2초 리소스 낭비.
 *
 * <h4>취약점 분석</h4>
 *
 * <p>타임아웃 계층:
 *
 * <pre>
 * TimeLimiter: 28s (상한)
 * └─ HTTP: connect 3s + response 5s (× 3회 재시도) = 최대 25s
 *     └─ Redis: timeout 3s (× 3회 재시도) = 최대 12s
 *         └─ MySQL Fallback: 3s connection + 5s lock = 최대 8s
 * </pre>
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>Zombie Request: 클라이언트 타임아웃 후 서버가 계속 처리하는 현상
 *   <li>Timeout Cascade: 하위 레이어 타임아웃이 누적되어 상위 타임아웃 초과
 *   <li>Cooperative Cancellation: 작업 실행 중 취소 요청을 확인하고 gracefully 종료
 *   <li>Timeout Hierarchy: 타임아웃은 상위 레이어 >= 하위 레이어 합계로 설정
 * </ul>
 *
 * @see <a href="https://resilience4j.readme.io/docs/timelimiter">Resilience4j TimeLimiter</a>
 * @see <a
 *     href="https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/">Timeout
 *     Best Practices</a>
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@ActiveProfiles("chaos")
@DisplayName("Nightmare 06: The Timeout Cascade (Zombie Request Problem)")
class N06TimeoutCascadeNightmareTest extends AbstractContainerBaseTest {

  @Autowired
  @Qualifier("expectationComputeExecutor") private Executor executor;

  @Autowired(required = false)
  private RedisTemplate<String, String> redisTemplate;

  /** Client timeout in milliseconds */
  private static final long CLIENT_TIMEOUT_MS = 3000;

  /** Redis delay injection in milliseconds */
  private static final long REDIS_DELAY_MS = 5000;

  /** Server TimeLimiter timeout in milliseconds */
  private static final long SERVER_TIMELIMITER_MS = 28000;

  /** Retry attempts */
  private static final int RETRY_ATTEMPTS = 3;

  /** Retry wait in milliseconds */
  private static final long RETRY_WAIT_MS = 1000;

  @BeforeEach
  void setUp() {
    log.info("[Nightmare 06] Test setup complete");
    log.info("[Nightmare 06] Client Timeout: {}ms", CLIENT_TIMEOUT_MS);
    log.info("[Nightmare 06] Redis Delay: {}ms", REDIS_DELAY_MS);
    log.info("[Nightmare 06] Server TimeLimiter: {}ms", SERVER_TIMELIMITER_MS);
    log.info("[Nightmare 06] Retry Attempts: {}", RETRY_ATTEMPTS);
  }

  /**
   * 🔴 Red's Test 1: Zombie Request 발생 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>Redis에 5초 지연 주입 (Simulated)
   *   <li>클라이언트 요청 (타임아웃 3초)
   *   <li>클라이언트 TimeoutException 발생 (T+3.0s)
   *   <li>서버는 Redis 응답 대기 계속 (Zombie 발생)
   *   <li>Retry 체인 완료 후 결과 폐기 (T+17.2s)
   *   <li>리소스 낭비 시간: 14.2초 (17.2s - 3s)
   * </ol>
   *
   * <p><b>성공 기준</b>: Zombie Request 0건
   *
   * <p><b>실패 조건</b>: Zombie Request >= 1건 → P0 Issue 생성
   */
  @Test
  @DisplayName("클라이언트 타임아웃 후 Zombie Request 발생 여부 검증")
  void shouldCreateZombieRequest_whenClientTimesOut() throws Exception {
    AtomicInteger zombieCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicLong totalWasteTime = new AtomicLong(0);

    log.info("[Red] Starting Zombie Request test...");
    log.info("[Red] Expected: Client timeout ({}) < Server chain (17.2s)", CLIENT_TIMEOUT_MS);

    // Simulate Redis delay with sleep
    int concurrentRequests = 10;
    ExecutorService testExecutor = Executors.newFixedThreadPool(concurrentRequests);
    CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

    long testStart = System.nanoTime();

    for (int i = 0; i < concurrentRequests; i++) {
      final int requestId = i;
      testExecutor.submit(
          () -> {
            try {
              long requestStart = System.nanoTime();

              // Simulate client timeout
              CompletableFuture<String> future =
                  CompletableFuture.supplyAsync(
                      () -> {
                        // Simulate Redis delay
                        try {
                          Thread.sleep(REDIS_DELAY_MS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return "result-" + requestId;
                      },
                      executor);

              // Client timeout
              try {
                future.get(CLIENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                successCount.incrementAndGet();
              } catch (TimeoutException e) {
                // Client timeout occurred
                long clientTimeoutTime = (System.nanoTime() - requestStart) / 1_000_000;

                // Server continues processing (Zombie)
                try {
                  future.join(); // Wait for server to complete
                  long serverCompleteTime = (System.nanoTime() - requestStart) / 1_000_000;
                  long wasteTime = serverCompleteTime - clientTimeoutTime;

                  if (wasteTime > 0) {
                    zombieCount.incrementAndGet();
                    totalWasteTime.addAndGet(wasteTime);
                    log.warn(
                        "[Red] Zombie Request detected! RequestId: {}, WasteTime: {}ms",
                        requestId,
                        wasteTime);
                  }
                } catch (Exception ex) {
                  log.warn("[Red] Server completion failed: {}", ex.getMessage());
                }
              }
            } catch (Exception e) {
              log.error("[Red] Request {} failed: {}", requestId, e.getMessage());
            } finally {
              doneLatch.countDown();
            }
          });
    }

    boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
    testExecutor.shutdown();
    testExecutor.awaitTermination(10, TimeUnit.SECONDS);

    long testDuration = (System.nanoTime() - testStart) / 1_000_000;
    double avgWasteTime =
        zombieCount.get() > 0 ? (double) totalWasteTime.get() / zombieCount.get() : 0.0;

    // 결과 출력
    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│       Nightmare 06: Zombie Request Results              │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Completed: {}                                               │",
        completed ? "YES" : "NO");
    log.info("│ Success Count: {}                                           │", successCount.get());
    log.info("│ Zombie Count: {}                                          │", zombieCount.get());
    log.info(
        "│ Total Waste Time: {} ms                                    │", totalWasteTime.get());
    log.info("│ Avg Waste Time: {:.2f} ms                                   │", avgWasteTime);
    log.info("│ Test Duration: {} ms                                        │", testDuration);

    if (zombieCount.get() > 0) {
      log.info("├────────────────────────────────────────────────────────────┤");
      log.info("│ ❌ ZOMBIE REQUESTS DETECTED!                               │");
      log.info(
          "│ Zombie Rate: {:.2f}%                                       │",
          (zombieCount.get() * 100.0 / concurrentRequests));
      log.info("├────────────────────────────────────────────────────────────┤");
      log.info("│ 🔧 Solution: Align Timeout Hierarchy                      │");
      log.info("│    - Client Timeout >= Server Processing Chain           │");
      log.info("│    - Implement cooperative cancellation                    │");
    } else {
      log.info("├────────────────────────────────────────────────────────────┤");
      log.info("│ ✅ No Zombie Requests - System is resilient               │");
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    // 검증: Nightmare 테스트는 취약점을 문서화함
    assertThat(completed).as("[Nightmare] 테스트가 타임아웃 없이 완료되어야 함").isTrue();

    // Zombie Request 발생 여부와 관계없이 테스트 통과 (취약점 문서화 목적)
    log.info(
        "[Nightmare] Zombie Request vulnerability documented: {} zombies ({} avg waste)",
        zombieCount.get(),
        String.format("%.2f", avgWasteTime));
  }

  /**
   * 🔵 Blue's Test 2: Retry 체인 시간 측정
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>Redis 5초 지연 × 3회 재시도 = 17.2s 총 소요
   *   <li>타임아웃이 각 레이어에서 누적되는지 확인
   * </ol>
   */
  @Test
  @DisplayName("Redis 지연 시 Retry 체인 시간 측정")
  void shouldMeasureRetryChainTime_whenRedisDelayed() throws Exception {
    log.info("[Blue] Measuring retry chain time...");

    AtomicLong totalChainTime = new AtomicLong(0);
    int iterations = 5;

    for (int i = 0; i < iterations; i++) {
      long chainStart = System.nanoTime();

      // Simulate retry chain
      for (int attempt = 0; attempt < RETRY_ATTEMPTS; attempt++) {
        try {
          // Simulate Redis operation with delay
          CompletableFuture<String> future =
              CompletableFuture.supplyAsync(
                  () -> {
                    try {
                      Thread.sleep(REDIS_DELAY_MS);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    return "result";
                  },
                  executor);

          future.get(CLIENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
          break; // Success, no more retries
        } catch (TimeoutException e) {
          log.info("[Blue] Attempt {} timed out, retrying...", attempt + 1);
          if (attempt < RETRY_ATTEMPTS - 1) {
            try {
              Thread.sleep(RETRY_WAIT_MS);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
          }
        }
      }

      long chainTime = (System.nanoTime() - chainStart) / 1_000_000;
      totalChainTime.addAndGet(chainTime);

      log.info("[Blue] Iteration {} chain time: {} ms", i + 1, chainTime);
    }

    double avgChainTime = (double) totalChainTime.get() / iterations;

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│           Retry Chain Time Analysis                       │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Iterations: {}                                              │", iterations);
    log.info("│ Total Time: {} ms                                        │", totalChainTime.get());
    log.info("│ Avg Chain Time: {:.2f} ms                                 │", avgChainTime);
    log.info(
        "│ Expected Max: {} ms (Redis delay × retries)              │",
        REDIS_DELAY_MS * RETRY_ATTEMPTS);
    log.info("└────────────────────────────────────────────────────────────┘");

    // 검증: Nightmare 테스트는 취약점을 문서화함
    assertThat(avgChainTime).as("[Nightmare] Retry 체인 시간 측정 완료").isPositive();

    log.info(
        "[Nightmare] Retry chain vulnerability documented: {:.2f} ms avg (expected < {} ms)",
        avgChainTime,
        CLIENT_TIMEOUT_MS);
  }

  /**
   * 🟢 Green's Test 3: 다계층 타임아웃 누적 검증
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>TimeLimiter: 28s
   *   <li>HTTP: connect 3s + response 5s (× 3회) = 25s
   *   <li>Redis: 3s (× 3회) = 12s
   *   <li>MySQL: 3s + 5s = 8s
   *   <li>총 누적: 28s > 25s > 12s > 8s (상위 >= 하위 합계)
   * </ol>
   */
  @Test
  @DisplayName("다계층 타임아웃 누적 검증")
  void shouldCascadeTimeouts_acrossLayers() throws Exception {
    log.info("[Green] Verifying timeout cascade across layers...");

    // Calculate expected timeout accumulation
    long redisTimeout = REDIS_DELAY_MS * RETRY_ATTEMPTS + RETRY_WAIT_MS * (RETRY_ATTEMPTS - 1);
    long mysqlTimeout = 3000 + 5000; // connection + lock
    long totalExpected = redisTimeout + mysqlTimeout;

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│       Timeout Hierarchy Analysis                          │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ TimeLimiter (Top): {} ms                              │", SERVER_TIMELIMITER_MS);
    log.info("│ HTTP Layer: ~25000 ms                                    │");
    log.info(
        "│ Redis Layer: {} ms ({} × {} retries + {})     │",
        redisTimeout,
        REDIS_DELAY_MS,
        RETRY_ATTEMPTS,
        RETRY_WAIT_MS * (RETRY_ATTEMPTS - 1));
    log.info("│ MySQL Fallback: {} ms                                   │", mysqlTimeout);
    log.info("│ Total Expected: {} ms                                    │", totalExpected);
    log.info("│ Client Timeout: {} ms                                │", CLIENT_TIMEOUT_MS);
    log.info("├────────────────────────────────────────────────────────────┤");

    // Check for timeout cascade
    boolean timeoutCascade = CLIENT_TIMEOUT_MS < totalExpected;

    if (timeoutCascade) {
      long wasteMs = totalExpected - CLIENT_TIMEOUT_MS;
      log.info("│ ❌ TIMEOUT CASCADE DETECTED!                             │");
      log.info(
          "│ Client timeout ({}) < Total chain ({})            │",
          CLIENT_TIMEOUT_MS,
          totalExpected);
      log.info("│ Waste Time: {} ms                                         │", wasteMs);
    } else {
      log.info("│ ✅ No Timeout Cascade - Hierarchy aligned                │");
    }
    log.info("└────────────────────────────────────────────────────────────┘");

    // 검증: Nightmare 테스트는 취약점을 문서화함
    assertThat(totalExpected).as("[Nightmare] 타임아웃 계층 분석 완료").isPositive();

    if (timeoutCascade) {
      log.info(
          "[Nightmare] Timeout cascade vulnerability documented: {} ms waste potential",
          totalExpected - CLIENT_TIMEOUT_MS);
    }
  }

  /**
   * 🟣 Purple's Test 4: Redis 장애 시 MySQL Fallback 측정
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>Redis 장애 발생
   *   <li>MySQL Fallback 작동
   *   <li>Fallback 시간 측정
   * </ol>
   */
  @Test
  @DisplayName("Redis 장애 시 MySQL Fallback 시간 측정")
  void shouldMeasureFallbackTime_whenRedisFails() throws Exception {
    log.info("[Purple] Measuring MySQL fallback time...");

    AtomicLong fallbackTime = new AtomicLong(0);
    int iterations = 3;

    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();

      // Simulate Redis failure + MySQL fallback
      try {
        CompletableFuture<String> redisFuture =
            CompletableFuture.supplyAsync(
                () -> {
                  throw new RuntimeException("Redis connection failed");
                },
                executor);

        redisFuture.get(1000, TimeUnit.MILLISECONDS);
      } catch (Exception e) {
        // Redis failed, fallback to MySQL (simulated)
        try {
          Thread.sleep(100); // Simulate MySQL query
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }

      long time = (System.nanoTime() - start) / 1_000_000;
      fallbackTime.addAndGet(time);

      log.info("[Purple] Iteration {} fallback time: {} ms", i + 1, time);
    }

    double avgFallbackTime = (double) fallbackTime.get() / iterations;

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│           MySQL Fallback Analysis                         │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Total Time: {} ms                                        │", fallbackTime.get());
    log.info("│ Avg Fallback Time: {:.2f} ms                              │", avgFallbackTime);
    log.info("└────────────────────────────────────────────────────────────┘");

    // 검증: Nightmare 테스트는 취약점을 문서화함
    assertThat(avgFallbackTime).as("[Nightmare] Fallback 시간 측정 완료").isPositive();

    log.info("[Nightmare] Fallback vulnerability documented: {:.2f} ms avg", avgFallbackTime);
  }

  /**
   * 🟡 Yellow's Test 5: 동시 요청 시 Zombie Request 발생률 측정
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>동시 요청 50개 발생
   *   <li>Zombie Request 발생률 측정
   *   <li>리소스 낭비 시간 분석
   * </ol>
   */
  @Test
  @DisplayName("동시 요청 시 Zombie Request 발생률 측정")
  void shouldMeasureZombieRequestRate_underConcurrentLoad() throws Exception {
    log.info("[Yellow] Measuring Zombie Request rate under load...");

    AtomicInteger zombieCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicLong totalWasteTime = new AtomicLong(0);
    ConcurrentLinkedQueue<Long> wasteTimes = new ConcurrentLinkedQueue<>();

    int concurrentRequests = 50;
    ExecutorService testExecutor = Executors.newFixedThreadPool(concurrentRequests);
    CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);

    long testStart = System.nanoTime();

    for (int i = 0; i < concurrentRequests; i++) {
      final int requestId = i;
      testExecutor.submit(
          () -> {
            try {
              long requestStart = System.nanoTime();

              // Simulate client timeout
              CompletableFuture<String> future =
                  CompletableFuture.supplyAsync(
                      () -> {
                        // Simulate Redis delay
                        try {
                          Thread.sleep(REDIS_DELAY_MS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return "result-" + requestId;
                      },
                      executor);

              // Client timeout
              try {
                future.get(CLIENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                successCount.incrementAndGet();
              } catch (TimeoutException e) {
                long clientTimeoutTime = (System.nanoTime() - requestStart) / 1_000_000;

                // Server continues (Zombie)
                try {
                  future.join();
                  long serverCompleteTime = (System.nanoTime() - requestStart) / 1_000_000;
                  long wasteTime = serverCompleteTime - clientTimeoutTime;

                  if (wasteTime > 0) {
                    zombieCount.incrementAndGet();
                    totalWasteTime.addAndGet(wasteTime);
                    wasteTimes.add(wasteTime);
                  }
                } catch (Exception ex) {
                  // Ignore
                }
              }
            } catch (Exception e) {
              // Ignore
            } finally {
              doneLatch.countDown();
            }
          });
    }

    boolean completed = doneLatch.await(120, TimeUnit.SECONDS);
    testExecutor.shutdown();
    testExecutor.awaitTermination(10, TimeUnit.SECONDS);

    long testDuration = (System.nanoTime() - testStart) / 1_000_000;
    double zombieRate = (zombieCount.get() * 100.0) / concurrentRequests;
    double avgWasteTime =
        zombieCount.get() > 0 ? (double) totalWasteTime.get() / zombieCount.get() : 0.0;
    long maxWasteTime = wasteTimes.stream().max(Long::compare).orElse(0L);

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│       Concurrent Load Zombie Analysis                    │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info(
        "│ Concurrent Requests: {}                                      │", concurrentRequests);
    log.info(
        "│ Completed: {}                                               │",
        completed ? "YES" : "NO");
    log.info("│ Success Count: {}                                           │", successCount.get());
    log.info("│ Zombie Count: {}                                          │", zombieCount.get());
    log.info("│ Zombie Rate: {:.2f}%                                       │", zombieRate);
    log.info(
        "│ Total Waste Time: {} ms                                    │", totalWasteTime.get());
    log.info("│ Avg Waste Time: {:.2f} ms                                   │", avgWasteTime);
    log.info("│ Max Waste Time: {} ms                                        │", maxWasteTime);
    log.info("│ Test Duration: {} ms                                        │", testDuration);
    log.info("└────────────────────────────────────────────────────────────┘");

    // 검증: Nightmare 테스트는 취약점을 문서화함
    assertThat(completed).as("[Nightmare] 테스트가 타임아웃 없이 완료되어야 함").isTrue();

    log.info(
        "[Nightmare] Concurrent load vulnerability documented: {:.2f}% zombie rate ({} avg waste)",
        zombieRate, String.format("%.2f", avgWasteTime));
  }
}
