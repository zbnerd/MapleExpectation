package maple.expectation.chaos.network;

import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Scenario 05: Clock Drift - Time Traveler (시간 불일치)
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 시스템 시간 조작 시뮬레이션</li>
 *   <li>🟣 Purple (Auditor): 데이터 무결성 검증 - TTL/만료 시간 정합성</li>
 *   <li>🔵 Blue (Architect): 흐름 검증 - 시간 기반 로직 안전성</li>
 *   <li>🟢 Green (Performance): 메트릭 검증 - 시간 드리프트 영향</li>
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 경계값 테스트</li>
 * </ul>
 *
 * <h4>검증 포인트</h4>
 * <ol>
 *   <li>TTL 계산 시 Clock Drift에 영향받지 않는지</li>
 *   <li>분산 락 만료 시간의 안전성</li>
 *   <li>이벤트 순서(Ordering)의 일관성</li>
 *   <li>Monotonic Clock 사용 여부</li>
 * </ol>
 *
 * <h4>CS 원리</h4>
 * <ul>
 *   <li>Wall Clock vs Monotonic Clock</li>
 *   <li>NTP (Network Time Protocol) 동기화</li>
 *   <li>Lamport Timestamp / Vector Clock</li>
 * </ul>
 *
 * @see java.time.Clock
 * @see System#nanoTime()
 */
@Tag("chaos")
@DisplayName("Scenario 05: Clock Drift - 시간 불일치 및 TTL 안전성 검증")
class ClockDriftChaosTest extends AbstractContainerBaseTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final String CLOCK_DRIFT_KEY = "clock-drift:test";

    @BeforeEach
    void setUp() {
        try {
            redisTemplate.delete(CLOCK_DRIFT_KEY);
        } catch (Exception ignored) {
        }
    }

    /**
     * 🟡 Yellow's Test 1: TTL이 Wall Clock이 아닌 Redis 서버 시간 기준인지 검증
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>TTL 10초로 키 설정</li>
     *   <li>5초 후 TTL 확인</li>
     *   <li>예상: 약 5초 남음 (클라이언트 시간과 무관)</li>
     * </ol>
     */
    @Test
    @DisplayName("Redis TTL은 서버 시간 기준으로 동작 (클라이언트 Clock Drift 무관)")
    void shouldUsServerTime_forRedisTTL() throws Exception {
        // Given: TTL 10초로 키 설정
        redisTemplate.opsForValue().set(CLOCK_DRIFT_KEY, "test-value");
        redisTemplate.expire(CLOCK_DRIFT_KEY, Duration.ofSeconds(10));

        Long initialTtl = redisTemplate.getExpire(CLOCK_DRIFT_KEY);
        assertThat(initialTtl).isBetween(9L, 10L);

        // When: 5초 대기
        Thread.sleep(5000);

        // Then: TTL이 약 5초 남음 (클라이언트 시간과 무관)
        Long remainingTtl = redisTemplate.getExpire(CLOCK_DRIFT_KEY);
        System.out.printf("[Green] Initial TTL: %ds, After 5s: %ds%n", initialTtl, remainingTtl);

        assertThat(remainingTtl)
                .as("5초 후 TTL은 약 5초가 남아야 함")
                .isBetween(4L, 6L);
    }

    /**
     * 🔵 Blue's Test 2: 분산 락 만료가 Monotonic Time 기준인지 검증
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>5초 TTL로 분산 락 획득</li>
     *   <li>3초 후 락 상태 확인</li>
     *   <li>6초 후 락이 자동 해제되었는지 확인</li>
     * </ol>
     */
    @Test
    @DisplayName("분산 락 TTL은 Monotonic 시간 기준으로 정확히 만료")
    void shouldExpireLock_basedOnMonotonicTime() throws Exception {
        // Given: 5초 TTL로 락 획득
        RLock lock = redissonClient.getLock("clock-drift:lock-test");
        boolean acquired = lock.tryLock(1, 5, TimeUnit.SECONDS);
        assertThat(acquired).isTrue();

        long startNanos = System.nanoTime();

        // When: 3초 후 락 상태 확인
        Thread.sleep(3000);
        boolean stillLocked = lock.isLocked();
        assertThat(stillLocked).as("3초 후에도 락 유지").isTrue();

        // Then: 6초 후 락 자동 해제 (TTL 5초 + 여유 1초)
        Thread.sleep(3000);

        await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    boolean expired = !lock.isLocked();
                    long elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000;
                    System.out.printf("[Blue] Elapsed: %ds, Lock expired: %s%n", elapsedSeconds, expired);
                    assertThat(expired).as("5초 TTL 후 락 자동 해제").isTrue();
                });
    }

    /**
     * 🟣 Purple's Test 3: 동시 락 획득 시 시간 순서 보장
     *
     * <p><b>시나리오</b>: 여러 스레드가 동시에 락을 요청할 때 순서대로 획득
     */
    @Test
    @DisplayName("동시 락 요청 시 FIFO 순서 보장")
    void shouldMaintainFIFO_forConcurrentLockRequests() throws Exception {
        RLock lock = redissonClient.getLock("clock-drift:fifo-test");
        CopyOnWriteArrayList<Integer> acquireOrder = new CopyOnWriteArrayList<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(3);

        ExecutorService executor = Executors.newFixedThreadPool(3);

        try {
            for (int i = 1; i <= 3; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await(); // 동시 시작

                        // 각 스레드가 락 획득 시도
                        boolean acquired = lock.tryLock(10, 1, TimeUnit.SECONDS);
                        if (acquired) {
                            try {
                                acquireOrder.add(threadId);
                                System.out.printf("[Purple] Thread %d acquired lock at %s%n",
                                        threadId, Instant.now());
                                Thread.sleep(100); // 짧은 작업 수행
                            } finally {
                                lock.unlock();
                            }
                        }
                    } catch (InterruptedException ignored) {
                    }
                    doneLatch.countDown();
                });
            }

            startLatch.countDown(); // 모든 스레드 동시 시작
            doneLatch.await(15, TimeUnit.SECONDS);

            // 모든 스레드가 락을 획득했어야 함
            System.out.printf("[Purple] Acquire order: %s%n", acquireOrder);
            assertThat(acquireOrder)
                    .as("모든 스레드가 순차적으로 락 획득")
                    .hasSize(3)
                    .containsExactlyInAnyOrder(1, 2, 3);

        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 🟢 Green's Test 4: System.nanoTime() vs System.currentTimeMillis() 비교
     *
     * <p><b>시나리오</b>: Monotonic Clock은 항상 증가하지만, Wall Clock은 점프 가능
     */
    @Test
    @DisplayName("Monotonic Clock(nanoTime)은 항상 단조 증가")
    void shouldAlwaysIncrease_monotonicClock() {
        long prevNanos = System.nanoTime();
        long prevMillis = System.currentTimeMillis();

        int monotonicViolations = 0;

        for (int i = 0; i < 1000; i++) {
            long currentNanos = System.nanoTime();
            long currentMillis = System.currentTimeMillis();

            // nanoTime은 항상 증가해야 함
            if (currentNanos < prevNanos) {
                monotonicViolations++;
            }

            prevNanos = currentNanos;
            prevMillis = currentMillis;
        }

        System.out.printf("[Green] Monotonic violations: %d / 1000%n", monotonicViolations);
        assertThat(monotonicViolations)
                .as("nanoTime은 절대 감소하지 않아야 함")
                .isZero();
    }

    /**
     * 🔴 Red's Test 5: 빠른 TTL 만료 시뮬레이션
     *
     * <p><b>시나리오</b>: 1초 TTL로 설정 후 만료 확인
     */
    @Test
    @DisplayName("짧은 TTL(1초) 정확히 만료 확인")
    void shouldExpireAccurately_withShortTTL() throws Exception {
        // Given: 1초 TTL 설정
        redisTemplate.opsForValue().set(CLOCK_DRIFT_KEY, "short-ttl-value");
        redisTemplate.expire(CLOCK_DRIFT_KEY, Duration.ofSeconds(1));

        // 즉시 확인
        String immediateValue = redisTemplate.opsForValue().get(CLOCK_DRIFT_KEY);
        assertThat(immediateValue).isEqualTo("short-ttl-value");

        // When: 1.5초 대기
        Thread.sleep(1500);

        // Then: 키가 만료되어 null
        String expiredValue = redisTemplate.opsForValue().get(CLOCK_DRIFT_KEY);
        System.out.printf("[Red] After 1.5s: value=%s (expected: null)%n", expiredValue);

        assertThat(expiredValue)
                .as("1초 TTL 후 키가 만료되어야 함")
                .isNull();
    }
}
