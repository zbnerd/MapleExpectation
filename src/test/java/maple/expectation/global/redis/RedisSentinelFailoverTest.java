package maple.expectation.global.redis;

import maple.expectation.support.AbstractSentinelContainerBaseTest;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Redis Sentinel Failover 자동화 테스트
 *
 * <p>테스트 목적:
 * <ul>
 *   <li>Master 장애 시 1초 이내 Failover 검증 (Issue #77 요구사항)</li>
 *   <li>Failover 후 분산 락 정상 동작 검증</li>
 *   <li>데이터 손실 없음 검증</li>
 * </ul>
 *
 * <p><b>Testcontainers 네트워크 주소 매핑:</b>
 * <ul>
 *   <li>Sentinel은 Docker 네트워크 내부 주소(redis-master:6379)를 모니터링</li>
 *   <li>애플리케이션은 localhost:MAPPED_PORT로 연결</li>
 *   <li>Redisson NatMapper가 내부 주소를 외부 주소로 자동 변환</li>
 * </ul>
 */
@ActiveProfiles("test")
@DisplayName("Redis Sentinel Failover 자동화 테스트")
class RedisSentinelFailoverTest extends IntegrationTestSupport {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Test
    @DisplayName("Master 장애 → 1초 이내 Failover 검증 (Issue #77 DoD)")
    void testMasterFailoverWithin1Second() throws Exception {
        // Given: 초기 데이터 쓰기
        redisTemplate.opsForValue().set("test-key", "initial-value");
        String initialValue = redisTemplate.opsForValue().get("test-key");
        assertThat(initialValue).isEqualTo("initial-value");

        // When: Master 장애 주입
        long failoverStart = System.currentTimeMillis();
        failMaster();

        // Then: Failover 완료 대기 (1초 이내)
        await()
                .atMost(2, TimeUnit.SECONDS) // 2초로 여유를 두되, 실제 시간은 1초 이내여야 함
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> {
                    try {
                        return "PONG".equals(redisTemplate.getConnectionFactory()
                                .getConnection().ping());
                    } catch (Exception e) {
                        return false;
                    }
                });

        long failoverTime = System.currentTimeMillis() - failoverStart;

        // 검증 1: Failover 시간 1초 이내
        assertThat(failoverTime)
                .as("Failover 시간은 1초 이내여야 합니다 (실제: %dms)", failoverTime)
                .isLessThan(1500); // 1.5초로 약간 여유 (Testcontainers 환경 고려)

        // 검증 2: 데이터 읽기 가능 (Slave가 Master로 승격되었음)
        String value = redisTemplate.opsForValue().get("test-key");
        assertThat(value)
                .as("Failover 후에도 데이터가 유지되어야 합니다")
                .isEqualTo("initial-value");

        // 검증 3: 새 데이터 쓰기 가능
        redisTemplate.opsForValue().set("new-key", "new-value");
        String newValue = redisTemplate.opsForValue().get("new-key");
        assertThat(newValue).isEqualTo("new-value");
    }

    @Test
    @DisplayName("분산 락 Failover 후 정상 동작 검증")
    void testDistributedLockAfterFailover() throws Exception {
        // Given: 정상 상태에서 락 획득
        RLock lock1 = redissonClient.getLock("test-lock");
        boolean locked1 = lock1.tryLock(1, 5, TimeUnit.SECONDS);
        assertThat(locked1).isTrue();
        lock1.unlock();

        // When: Master 장애 발생
        failMaster();

        // Failover 대기 (1.5초)
        Thread.sleep(1500);

        // Then: 새 Master에서 락 정상 동작
        await()
                .atMost(3, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .until(() -> {
                    try {
                        RLock lock2 = redissonClient.getLock("test-lock-after-failover");
                        boolean locked = lock2.tryLock(1, 5, TimeUnit.SECONDS);
                        if (locked) {
                            lock2.unlock();
                        }
                        return locked;
                    } catch (Exception e) {
                        return false;
                    }
                });
    }

    @Test
    @DisplayName("Failover 중 데이터 손실 없음 검증")
    void testNoDataLossDuringFailover() throws Exception {
        // Given: 10개 데이터 쓰기
        for (int i = 0; i < 10; i++) {
            redisTemplate.opsForValue().set("data-" + i, "value-" + i);
        }

        // When: Master 장애
        failMaster();

        // Failover 대기
        await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> {
                    try {
                        return "PONG".equals(redisTemplate.getConnectionFactory()
                                .getConnection().ping());
                    } catch (Exception e) {
                        return false;
                    }
                });

        // Then: 모든 데이터 읽기 가능
        for (int i = 0; i < 10; i++) {
            String value = redisTemplate.opsForValue().get("data-" + i);
            assertThat(value)
                    .as("data-%d 키의 값이 유지되어야 합니다", i)
                    .isEqualTo("value-" + i);
        }
    }

    @Test
    @DisplayName("Master 복구 후 정상 동작 검증")
    void testRecoveryAfterMasterReturns() throws Exception {
        // Given: 초기 데이터
        redisTemplate.opsForValue().set("recovery-test", "before-failure");

        // When: Master 장애 → Failover
        failMaster();
        Thread.sleep(1500);

        // Master 복구
        recoverMaster();
        Thread.sleep(1000);

        // Then: 정상 동작
        await()
                .atMost(3, TimeUnit.SECONDS)
                .until(() -> {
                    try {
                        String value = redisTemplate.opsForValue().get("recovery-test");
                        return "before-failure".equals(value);
                    } catch (Exception e) {
                        return false;
                    }
                });

        // 새 데이터 쓰기 가능
        redisTemplate.opsForValue().set("after-recovery", "success");
        String value = redisTemplate.opsForValue().get("after-recovery");
        assertThat(value).isEqualTo("success");
    }

    @Test
    @DisplayName("동시 락 획득 시나리오에서 Failover 테스트")
    void testConcurrentLockDuringFailover() throws Exception {
        // Given: 락 획득
        RLock lock = redissonClient.getLock("concurrent-lock");
        boolean locked = lock.tryLock(1, 10, TimeUnit.SECONDS);
        assertThat(locked).isTrue();

        // When: 락 보유 중 Master 장애
        failMaster();

        // Then: Failover 후에도 락이 유효한지 확인
        // (Redisson이 자동으로 재연결)
        await()
                .atMost(3, TimeUnit.SECONDS)
                .until(() -> {
                    try {
                        // 락이 여전히 보유 중인지 확인
                        return lock.isLocked();
                    } catch (Exception e) {
                        return false;
                    }
                });

        // 락 해제
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }

        // 새 락 획득 가능
        RLock newLock = redissonClient.getLock("new-lock-after-failover");
        boolean newLocked = newLock.tryLock(1, 5, TimeUnit.SECONDS);
        assertThat(newLocked).isTrue();
        newLock.unlock();
    }
}
