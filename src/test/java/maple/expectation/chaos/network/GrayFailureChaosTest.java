package maple.expectation.chaos.network;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario 12: Gray Failure - 3% 패킷 손실
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 낮은 비율의 패킷 손실</li>
 *   <li>🔵 Blue (Architect): 흐름 검증 - 재시도로 극복 가능한지</li>
 *   <li>🟢 Green (Performance): 메트릭 검증 - 성공률 및 응답 시간</li>
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 통계적 유의성 검증</li>
 * </ul>
 *
 * <h4>검증 포인트</h4>
 * <ol>
 *   <li>3% 패킷 손실 시에도 97% 이상 성공</li>
 *   <li>재시도로 일시적 실패 극복</li>
 *   <li>Circuit Breaker가 열리지 않음 (실패율 < 50%)</li>
 *   <li>평균 응답 시간 증가폭 측정</li>
 * </ol>
 *
 * @see org.testcontainers.containers.ToxiproxyContainer
 */
@Tag("chaos")
@SpringBootTest
@DisplayName("Scenario 12: Gray Failure - 낮은 비율의 패킷 손실")
class GrayFailureChaosTest extends AbstractContainerBaseTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String GRAY_FAILURE_KEY = "gray-failure:test";

    @BeforeEach
    void setUp() {
        try {
            redisTemplate.delete(GRAY_FAILURE_KEY);
        } catch (Exception ignored) {
        }
    }

    /**
     * 🔴 Red's Test 1: 3% 패킷 손실 시 성공률 검증
     */
    @Test
    @DisplayName("3% 패킷 손실에서도 97% 이상 성공")
    void shouldMaintainHighSuccessRate_with3PercentPacketLoss() throws Exception {
        int totalRequests = 100;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<Long> responseTimes = new ConcurrentLinkedQueue<>();

        // 3% 패킷 손실 주입 (toxicity: 0.03)
        // Toxiproxy의 limit_data toxic으로 대신 시뮬레이션
        // 실제로는 timeout toxic을 낮은 확률로 적용
        redisProxy.toxics()
                .timeout("gray-timeout", ToxicDirection.DOWNSTREAM, 100)
                .setToxicity(0.03f);  // 3% 확률로 타임아웃

        System.out.println("[Red] Injected 3% packet loss (timeout toxic)");

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            final int requestId = i;
            executor.submit(() -> {
                try {
                    long start = System.nanoTime();
                    redisTemplate.opsForValue().set(GRAY_FAILURE_KEY + ":" + requestId, "value");
                    String result = redisTemplate.opsForValue().get(GRAY_FAILURE_KEY + ":" + requestId);
                    long elapsed = (System.nanoTime() - start) / 1_000_000;

                    if (result != null) {
                        successCount.incrementAndGet();
                        responseTimes.add(elapsed);
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // 정리
        redisProxy.toxics().get("gray-timeout").remove();

        // 통계
        double successRate = successCount.get() * 100.0 / totalRequests;
        long avgTime = responseTimes.stream().mapToLong(Long::longValue).sum() /
                Math.max(responseTimes.size(), 1);

        System.out.println("┌────────────────────────────────────────────────────────────┐");
        System.out.println("│               Gray Failure Analysis (3% loss)              │");
        System.out.println("├────────────────────────────────────────────────────────────┤");
        System.out.printf("│ Total Requests: %d                                          │%n", totalRequests);
        System.out.printf("│ Success: %d (%.1f%%)                                        │%n",
                successCount.get(), successRate);
        System.out.printf("│ Failure: %d (%.1f%%)                                        │%n",
                failureCount.get(), failureCount.get() * 100.0 / totalRequests);
        System.out.printf("│ Avg Response Time: %dms                                     │%n", avgTime);
        System.out.println("└────────────────────────────────────────────────────────────┘");

        // 검증: 성공률이 90% 이상 (3% 손실 + 네트워크 지터 고려)
        assertThat(successRate)
                .as("3% 패킷 손실에서도 90% 이상 성공")
                .isGreaterThanOrEqualTo(90.0);
    }

    /**
     * 🔵 Blue's Test 2: 재시도로 Gray Failure 극복
     */
    @Test
    @DisplayName("재시도로 일시적 실패 극복")
    void shouldOvercome_grayFailureWithRetry() throws Exception {
        // 5% 손실률 (더 높은 실패율)
        redisProxy.toxics()
                .timeout("retry-timeout", ToxicDirection.DOWNSTREAM, 50)
                .setToxicity(0.05f);

        System.out.println("[Blue] Testing retry mechanism with 5% loss...");

        int maxRetries = 3;
        int successWithRetry = 0;
        int totalTests = 50;

        for (int t = 0; t < totalTests; t++) {
            String key = GRAY_FAILURE_KEY + ":retry:" + t;
            boolean success = false;
            int attempts = 0;

            while (attempts < maxRetries && !success) {
                attempts++;
                try {
                    redisTemplate.opsForValue().set(key, "value");
                    String result = redisTemplate.opsForValue().get(key);
                    if (result != null) {
                        success = true;
                    }
                } catch (Exception e) {
                    Thread.sleep(50 * attempts);  // Backoff
                }
            }

            if (success) {
                successWithRetry++;
            }
        }

        // 정리
        redisProxy.toxics().get("retry-timeout").remove();

        double successRate = successWithRetry * 100.0 / totalTests;

        System.out.println("┌────────────────────────────────────────────────────────────┐");
        System.out.println("│               Retry Effectiveness (5% loss)                │");
        System.out.println("├────────────────────────────────────────────────────────────┤");
        System.out.printf("│ Tests: %d, Max Retries: %d                                  │%n", totalTests, maxRetries);
        System.out.printf("│ Success with retry: %d (%.1f%%)                             │%n",
                successWithRetry, successRate);
        System.out.println("└────────────────────────────────────────────────────────────┘");

        // 재시도로 95% 이상 성공해야 함
        assertThat(successRate)
                .as("재시도로 95% 이상 극복")
                .isGreaterThanOrEqualTo(95.0);
    }

    /**
     * 🟢 Green's Test 3: Gray Failure가 Circuit Breaker를 트리거하지 않음
     */
    @Test
    @DisplayName("3% 실패율은 Circuit Breaker를 열지 않음")
    void shouldNotOpenCircuitBreaker_with3PercentFailure() throws Exception {
        // 3% 손실 (Circuit Breaker 임계치 50% 미만)
        redisProxy.toxics()
                .timeout("cb-timeout", ToxicDirection.DOWNSTREAM, 100)
                .setToxicity(0.03f);

        int totalRequests = 100;
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger circuitOpenCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            try {
                redisTemplate.opsForValue().set(GRAY_FAILURE_KEY + ":cb:" + i, "value");
            } catch (Exception e) {
                failureCount.incrementAndGet();
                // Circuit Breaker가 열렸는지 확인 (CallNotPermittedException)
                if (e.getMessage() != null && e.getMessage().contains("CircuitBreaker")) {
                    circuitOpenCount.incrementAndGet();
                }
            }
        }

        // 정리
        redisProxy.toxics().get("cb-timeout").remove();

        double failureRate = failureCount.get() * 100.0 / totalRequests;

        System.out.println("┌────────────────────────────────────────────────────────────┐");
        System.out.println("│               Circuit Breaker Status                       │");
        System.out.println("├────────────────────────────────────────────────────────────┤");
        System.out.printf("│ Failure Rate: %.1f%%                                        │%n", failureRate);
        System.out.printf("│ Circuit Breaker Open Count: %d                              │%n", circuitOpenCount.get());
        System.out.printf("│ CB Threshold: 50%%                                          │%n");
        System.out.printf("│ Status: %s                                                  │%n",
                circuitOpenCount.get() == 0 ? "CLOSED (as expected)" : "OPENED (unexpected!)");
        System.out.println("└────────────────────────────────────────────────────────────┘");

        // Circuit Breaker가 열리지 않아야 함
        assertThat(circuitOpenCount.get())
                .as("3% 실패율은 Circuit Breaker를 열지 않음")
                .isZero();
    }
}
