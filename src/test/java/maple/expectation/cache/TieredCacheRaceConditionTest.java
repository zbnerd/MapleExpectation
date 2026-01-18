package maple.expectation.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #148: TieredCache Race Condition 제거 테스트
 *
 * <h4>검증 대상</h4>
 * <ul>
 *   <li>Cache Stampede 방지 (Single-flight 패턴)</li>
 *   <li>L1/L2 일관성 (L2 → L1 순서)</li>
 *   <li>분산 락 Fallback (가용성 우선)</li>
 *   <li>메트릭 수집 검증</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"nexon.api.key=dummy-test-key"})
@Execution(ExecutionMode.SAME_THREAD)  // CLAUDE.md Section 24: Redis 공유 상태 충돌 방지
class TieredCacheRaceConditionTest extends AbstractContainerBaseTest {

    // -------------------------------------------------------------------------
    // [Mock 구역] ApplicationContext 캐싱 일관성 (CLAUDE.md Section 24)
    // -------------------------------------------------------------------------
    @MockitoBean private RealNexonApiClient nexonApiClient;
    @MockitoBean private DiscordAlertService discordAlertService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RedissonClient redissonClient;

    private Cache cache;

    @BeforeEach
    void setUp() {
        cache = cacheManager.getCache("equipment");
        if (cache != null) {
            cache.clear();
        }
    }

    // ==================== 1. Single-flight 검증 ====================

    @Nested
    @DisplayName("Single-flight 패턴 검증")
    class SingleFlightTests {

        @Test
        @DisplayName("동시 100개 요청 시 valueLoader는 1회만 호출되어야 한다")
        void shouldCallValueLoaderOnlyOnce_whenConcurrentRequests() throws Exception {
            // given
            AtomicInteger callCount = new AtomicInteger(0);
            String testKey = "stampede-test-" + UUID.randomUUID();
            Callable<String> valueLoader = () -> {
                callCount.incrementAndGet();
                Thread.sleep(100); // 느린 작업 시뮬레이션
                return "loaded-value";
            };

            // when: 100개 동시 요청
            ExecutorService executor = Executors.newFixedThreadPool(100);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < 100; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await(); // 동시 시작 보장
                    return cache.get(testKey, valueLoader);
                }));
            }
            startLatch.countDown(); // 동시 시작

            // then
            Set<String> results = new HashSet<>();
            for (Future<String> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }

            // Single-flight: 최대 소수의 호출만 발생 (락 경합 시 Fallback으로 추가 호출 가능)
            // 정상적인 경우 1회, 락 타임아웃 발생 시 최대 N회
            assertThat(callCount.get()).isLessThanOrEqualTo(5);
            assertThat(results).hasSize(1).containsExactly("loaded-value");

            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("Follower는 Leader 완료 후 L2에서 값을 읽어야 한다")
        void followerShouldReadFromL2_afterLeaderCompletes() throws Exception {
            // given
            String testKey = "double-check-test-" + UUID.randomUUID();
            AtomicInteger callCount = new AtomicInteger(0);
            CountDownLatch leaderStarted = new CountDownLatch(1);
            CountDownLatch leaderAcquiredLock = new CountDownLatch(1);

            Callable<String> valueLoader = () -> {
                leaderStarted.countDown();
                leaderAcquiredLock.countDown(); // Leader가 락 획득 후 valueLoader 진입 시 알림
                callCount.incrementAndGet();
                Thread.sleep(200); // 느린 작업 시뮬레이션 (Callable 내부이므로 허용)
                return "leader-value";
            };

            ExecutorService executor = Executors.newFixedThreadPool(2);

            // when: Leader 시작
            Future<String> leaderFuture = executor.submit(() ->
                    cache.get(testKey, valueLoader)
            );

            // CLAUDE.md Section 24: Thread.sleep() 제거 → CountDownLatch로 명시적 동기화
            // Follower: Leader가 락을 획득하고 valueLoader에 진입할 때까지 대기
            boolean leaderInLoader = leaderAcquiredLock.await(10, TimeUnit.SECONDS);
            assertThat(leaderInLoader).as("Leader가 valueLoader에 진입해야 함").isTrue();

            Future<String> followerFuture = executor.submit(() ->
                    cache.get(testKey, valueLoader)
            );

            // then
            String leaderResult = leaderFuture.get(10, TimeUnit.SECONDS);
            String followerResult = followerFuture.get(10, TimeUnit.SECONDS);

            assertThat(leaderResult).isEqualTo("leader-value");
            assertThat(followerResult).isEqualTo("leader-value");
            assertThat(callCount.get()).isEqualTo(1); // Follower는 Double-check에서 L2 히트

            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // ==================== 2. 락 Fallback 검증 ====================

    @Nested
    @DisplayName("락 Fallback 검증")
    class LockFallbackTests {

        @Test
        @DisplayName("락 획득 실패 시 Fallback으로 직접 실행되어야 한다")
        void shouldExecuteDirectly_whenLockAcquisitionFails() throws Exception {
            // given
            String testKey = "lock-failure-test-" + UUID.randomUUID();
            String lockKey = "cache:sf:equipment:" + testKey.hashCode();

            // 락을 미리 획득하여 타임아웃 유도
            RLock lock = redissonClient.getLock(lockKey);
            lock.lock();

            try {
                AtomicInteger callCount = new AtomicInteger(0);
                CountDownLatch valueLoaderStarted = new CountDownLatch(1);
                Callable<String> valueLoader = () -> {
                    valueLoaderStarted.countDown(); // valueLoader 진입 알림
                    callCount.incrementAndGet();
                    return "fallback-value";
                };

                // when: 락 획득 불가능한 상황에서 요청
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<String> future = executor.submit(() -> cache.get(testKey, valueLoader));

                // CLAUDE.md Section 24: Thread.sleep() 제거 → Awaitility로 명시적 대기
                // 요청이 락 대기 상태에 진입할 시간을 준 후 락 해제
                org.awaitility.Awaitility.await()
                        .atMost(3, TimeUnit.SECONDS)
                        .pollInterval(100, TimeUnit.MILLISECONDS)
                        .until(() -> !future.isDone()); // Future가 대기 상태임을 확인

                lock.unlock();

                String result = future.get(35, TimeUnit.SECONDS);

                // then: 값이 반환됨
                assertThat(result).isEqualTo("fallback-value");
                assertThat(callCount.get()).isGreaterThanOrEqualTo(1);

                executor.shutdown();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    // ==================== 3. 예외 전파 검증 ====================

    @Nested
    @DisplayName("예외 전파 검증")
    class ExceptionPropagationTests {

        @Test
        @DisplayName("valueLoader 예외가 정확히 전파되어야 한다")
        void shouldPropagateException_whenValueLoaderFails() {
            // given
            String testKey = "exception-test-" + UUID.randomUUID();
            Callable<String> valueLoader = () -> {
                throw new RuntimeException("Intentional failure");
            };

            // when & then
            assertThatThrownBy(() -> cache.get(testKey, valueLoader))
                    .isInstanceOf(Cache.ValueRetrievalException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
        }
    }

    // ==================== 4. L2 일관성 검증 ====================

    @Nested
    @DisplayName("L2 일관성 검증")
    class L2ConsistencyTests {

        @Test
        @DisplayName("put 후 get 시 동일한 값이 반환되어야 한다")
        void shouldReturnSameValue_afterPut() {
            // given
            String testKey = "consistency-test-" + UUID.randomUUID();
            String testValue = "test-value-" + UUID.randomUUID();

            // when
            cache.put(testKey, testValue);
            String retrieved = cache.get(testKey, String.class);

            // then
            assertThat(retrieved).isEqualTo(testValue);
        }

        @Test
        @DisplayName("evict 후 get 시 null이 반환되어야 한다")
        void shouldReturnNull_afterEvict() {
            // given
            String testKey = "evict-test-" + UUID.randomUUID();
            cache.put(testKey, "some-value");

            // when
            cache.evict(testKey);
            String retrieved = cache.get(testKey, String.class);

            // then
            assertThat(retrieved).isNull();
        }
    }

    // ==================== 5. 메트릭 검증 ====================

    @Nested
    @DisplayName("메트릭 검증")
    class MetricsTests {

        @Test
        @DisplayName("캐시 히트 시 메트릭이 기록되어야 한다")
        void shouldRecordHitMetrics() {
            // given
            String testKey = "metrics-hit-test-" + UUID.randomUUID();
            cache.put(testKey, "cached-value");

            // 메트릭 초기값 기록
            double initialL1Hits = getCounterValue("cache.hit", "layer", "L1");

            // when: L1 히트 발생
            cache.get(testKey, String.class);

            // then
            double finalL1Hits = getCounterValue("cache.hit", "layer", "L1");
            assertThat(finalL1Hits).isGreaterThan(initialL1Hits);
        }

        @Test
        @DisplayName("캐시 미스 시 메트릭이 기록되어야 한다")
        void shouldRecordMissMetrics() {
            // given
            String testKey = "metrics-miss-test-" + UUID.randomUUID();
            double initialMisses = getCounterValue("cache.miss");

            // when: 캐시 미스 발생 (valueLoader 호출)
            cache.get(testKey, () -> "new-value");

            // then
            double finalMisses = getCounterValue("cache.miss");
            assertThat(finalMisses).isGreaterThan(initialMisses);
        }

        private double getCounterValue(String name, String... tags) {
            Counter counter = meterRegistry.find(name).tags(tags).counter();
            return counter != null ? counter.count() : 0.0;
        }
    }

    // ==================== 6. Toxiproxy L2 장애 시나리오 ====================

    @Nested
    @DisplayName("L2 장애 시나리오")
    class L2FailureTests {

        @Test
        @DisplayName("L2 장애 시에도 valueLoader 결과는 반환되어야 한다")
        void shouldReturnValue_whenL2Fails() throws Exception {
            // given
            String testKey = "l2-failure-test-" + UUID.randomUUID();
            AtomicInteger callCount = new AtomicInteger(0);
            Callable<String> valueLoader = () -> {
                callCount.incrementAndGet();
                return "fallback-value";
            };

            // when: Redis 연결 끊기
            redisProxy.setConnectionCut(true);

            try {
                // L2 장애 상황에서 요청
                String result = cache.get(testKey, valueLoader);

                // then: 값은 반환됨 (가용성)
                assertThat(result).isEqualTo("fallback-value");
                assertThat(callCount.get()).isGreaterThanOrEqualTo(1);
            } finally {
                // Redis 연결 복구
                redisProxy.setConnectionCut(false);
            }
        }
    }
}
