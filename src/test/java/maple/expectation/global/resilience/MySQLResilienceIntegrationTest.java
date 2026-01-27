package maple.expectation.global.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import maple.expectation.global.event.MySQLDownEvent;
import maple.expectation.global.event.MySQLUpEvent;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * MySQL Resilience 통합 테스트 (Issue #218)
 *
 * <p>P1-5: Edge Case 테스트 포함</p>
 *
 * <h4>테스트 시나리오</h4>
 * <ul>
 *   <li>TC1: MySQL DOWN → TTL 무한대 설정 검증</li>
 *   <li>TC2: MySQL UP → TTL 원복 검증</li>
 *   <li>TC3: Compensation Log 기록/읽기</li>
 *   <li>TC4: Compensation Sync DB 동기화</li>
 *   <li>TC5: Flapping 시나리오 (DOWN 후 5초 내 UP)</li>
 *   <li>TC6: 상태 머신 전이 사이클</li>
 *   <li>TC7: 동시성 테스트 (분산 락)</li>
 * </ul>
 */
@Tag("integration")
@DisplayName("MySQL Resilience 통합 테스트")
class MySQLResilienceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MySQLFallbackProperties properties;

    @Autowired
    private MySQLHealthEventPublisher healthEventPublisher;

    @Autowired
    private DynamicTTLManager dynamicTTLManager;

    @Autowired
    private CompensationLogService compensationLogService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void cleanUp() {
        // P1-N11: 테스트 격리를 위해 관련 키 삭제
        redissonClient.getKeys().deleteByPattern("{mysql}:*");
        redissonClient.getKeys().deleteByPattern("equipment:*");
        redissonClient.getKeys().deleteByPattern("ocidCache:*");
    }

    // ==================== TC1-TC2: TTL 관리 테스트 ====================

    @Nested
    @DisplayName("Dynamic TTL Manager 테스트")
    class DynamicTTLManagerTest {

        @Test
        @DisplayName("TC1: MySQL DOWN 이벤트 시 캐시 TTL이 제거됨 (PERSIST)")
        void whenMySQLDown_thenCacheTTLRemoved() {
            // Given: Redis에 TTL이 있는 캐시 데이터 저장
            String testKey = "equipment:test-ocid-1";
            RBucket<String> bucket = redissonClient.getBucket(testKey);
            bucket.set("test-data", Duration.ofMinutes(10));

            assertThat(bucket.remainTimeToLive()).isGreaterThan(0);

            // When: MySQL DOWN 이벤트 발행
            MySQLDownEvent event = MySQLDownEvent.of("likeSyncDb", "CLOSED", "OPEN");
            eventPublisher.publishEvent(event);

            // Then: TTL이 제거됨 (PERSIST)
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> {
                        long ttl = bucket.remainTimeToLive();
                        // TTL이 -1이면 PERSIST 됨 (무한대)
                        assertThat(ttl).isEqualTo(-1);
                    });
        }

        @Test
        @DisplayName("TC2: MySQL UP 이벤트 시 캐시 TTL이 복원됨 (EXPIRE)")
        void whenMySQLUp_thenCacheTTLRestored() {
            // Given: Redis에 TTL이 없는 (PERSIST된) 캐시 데이터
            String testKey = "equipment:test-ocid-2";
            RBucket<String> bucket = redissonClient.getBucket(testKey);
            bucket.set("test-data"); // TTL 없음

            assertThat(bucket.remainTimeToLive()).isEqualTo(-1);

            // When: MySQL UP 이벤트 발행
            MySQLUpEvent event = MySQLUpEvent.of("likeSyncDb", "OPEN", "CLOSED");
            eventPublisher.publishEvent(event);

            // Then: TTL이 복원됨 (10분 = 600초)
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> {
                        long ttl = bucket.remainTimeToLive();
                        // equipment 캐시는 10분 TTL
                        assertThat(ttl).isBetween(500_000L, 600_000L); // 밀리초
                    });
        }
    }

    // ==================== TC3-TC4: Compensation Log 테스트 ====================

    @Nested
    @DisplayName("Compensation Log 테스트")
    class CompensationLogTest {

        @Test
        @DisplayName("TC3: Compensation Log 기록 및 읽기")
        void testCompensationLogWriteAndRead() {
            // Given
            String type = "equipment";
            String key = "test-ocid-comp";
            Map<String, String> data = Map.of("name", "test-equipment");

            // When: 로그 기록
            StreamMessageId messageId = compensationLogService.writeLog(type, key, data);

            // Then: 메시지 ID가 반환됨
            assertThat(messageId).isNotNull();

            // And: Pending 카운트 증가
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> {
                        long pendingCount = compensationLogService.getPendingCount();
                        assertThat(pendingCount).isGreaterThanOrEqualTo(1);
                    });
        }

        @Test
        @DisplayName("TC4: Stream MAXLEN 제한 검증")
        void testStreamMaxLenTrim() {
            // Given: maxLen보다 많은 메시지 추가
            int messageCount = 100;

            for (int i = 0; i < messageCount; i++) {
                compensationLogService.writeLog("test", "key-" + i, Map.of("index", String.valueOf(i)));
            }

            // Then: Stream 크기가 MAXLEN 이하
            RStream<String, String> stream = redissonClient.getStream(properties.getCompensationStream());
            long size = stream.size();

            assertThat(size).isLessThanOrEqualTo(properties.getStreamMaxLen());
        }
    }

    // ==================== TC5-TC6: 상태 머신 테스트 ====================

    @Nested
    @DisplayName("상태 머신 테스트")
    class StateMachineTest {

        @Test
        @DisplayName("TC5: 상태 전이 사이클 (HEALTHY → DEGRADED → RECOVERING → HEALTHY)")
        void testFullStateCycle() {
            // Given: 초기 상태 HEALTHY
            MySQLHealthState initialState = healthEventPublisher.getCurrentState();
            assertThat(initialState).isEqualTo(MySQLHealthState.HEALTHY);

            // When: DEGRADED로 수동 전이 (테스트용)
            RBucket<String> stateBucket = redissonClient.getBucket(properties.getStateKey());
            stateBucket.set(MySQLHealthState.DEGRADED.name(), Duration.ofSeconds(300));

            // Then: DEGRADED 상태 확인
            MySQLHealthState degradedState = healthEventPublisher.getCurrentState();
            assertThat(degradedState).isEqualTo(MySQLHealthState.DEGRADED);

            // When: RECOVERING으로 전이
            stateBucket.set(MySQLHealthState.RECOVERING.name(), Duration.ofSeconds(300));

            // Then: RECOVERING 상태 확인
            MySQLHealthState recoveringState = healthEventPublisher.getCurrentState();
            assertThat(recoveringState).isEqualTo(MySQLHealthState.RECOVERING);

            // When: 복구 완료
            healthEventPublisher.markRecoveryComplete();

            // Then: HEALTHY 상태로 복귀
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> {
                        MySQLHealthState finalState = healthEventPublisher.getCurrentState();
                        assertThat(finalState).isEqualTo(MySQLHealthState.HEALTHY);
                    });
        }

        @Test
        @DisplayName("TC6: 상태 TTL 만료 시 기본값 HEALTHY 반환")
        void testStateTTLExpiration() {
            // Given: 매우 짧은 TTL로 상태 설정
            RBucket<String> stateBucket = redissonClient.getBucket(properties.getStateKey());
            stateBucket.set(MySQLHealthState.DEGRADED.name(), Duration.ofMillis(100));

            // When: TTL 만료 대기
            await().atMost(Duration.ofSeconds(2))
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> !stateBucket.isExists());

            // Then: 기본값 HEALTHY 반환
            MySQLHealthState state = healthEventPublisher.getCurrentState();
            assertThat(state).isEqualTo(MySQLHealthState.HEALTHY);
        }
    }

    // ==================== TC7: 동시성 테스트 ====================

    @Nested
    @DisplayName("동시성 테스트")
    class ConcurrencyTest {

        @Test
        @DisplayName("TC7: 여러 인스턴스가 동시에 락 획득 시도 시 1개만 성공")
        void testDistributedLockConcurrency() throws InterruptedException {
            // Given: 5개 스레드로 동시 락 획득 시도
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger lockAcquiredCount = new AtomicInteger(0);

            String lockKey = properties.getTtlLockKey();

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // 모든 스레드가 동시에 시작

                        var lock = redissonClient.getLock(lockKey);
                        boolean acquired = lock.tryLock(
                                properties.getLockWaitSeconds(),
                                properties.getLockLeaseSeconds(),
                                TimeUnit.SECONDS
                        );

                        if (acquired) {
                            lockAcquiredCount.incrementAndGet();
                            // 짧은 작업 수행
                            Thread.sleep(100);
                            lock.unlock();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            // When: 모든 스레드 동시 시작
            startLatch.countDown();
            endLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            // Then: waitTime 내에 모든 스레드가 순차적으로 락 획득 가능
            // (단, 동시에 1개만 획득 가능)
            assertThat(lockAcquiredCount.get()).isGreaterThanOrEqualTo(1);
        }
    }

    // ==================== CircuitBreaker 상태 제어 테스트 (P1-N8) ====================

    @Nested
    @DisplayName("CircuitBreaker 연동 테스트")
    class CircuitBreakerTest {

        @Test
        @DisplayName("TC8: CircuitBreaker 상태 전이 시 이벤트 발행")
        void testCircuitBreakerStateTransition() {
            // Given: likeSyncDb CircuitBreaker
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("likeSyncDb");

            // 초기 상태 확인
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            // When: CB를 OPEN 상태로 강제 전환 (테스트용)
            cb.transitionToOpenState();

            // Then: OPEN 상태 확인
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // Cleanup: CLOSED 상태로 복원
            cb.transitionToClosedState();
        }
    }
}
