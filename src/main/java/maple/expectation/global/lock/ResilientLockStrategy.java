package maple.expectation.global.lock;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 🛡️ 회복력 있는 락 전략 (Redis 우선, 실패 시 MySQL로 복구)
 * LogicExecutor를 사용하여 모든 try-catch를 제거하고, 2단계 락 메커니즘을 선언적으로 구현했습니다.
 * * [변경 사항]
 * - Redis 락 획득 시 '즉시 시도'가 아닌 'waitTime 대기'로 변경하여
 * 일시적인 락 경합 시 MySQL로 트래픽이 새는 것(Connection Exhaustion)을 방지함.
 */
@Slf4j
@Primary
@Component
public class ResilientLockStrategy extends AbstractLockStrategy {

    private final LockStrategy redisLockStrategy;
    private final LockStrategy mysqlLockStrategy;
    private final CircuitBreaker circuitBreaker;

    public ResilientLockStrategy(
            @Qualifier("redisDistributedLockStrategy") LockStrategy redisLockStrategy,
            MySqlNamedLockStrategy mysqlLockStrategy,
            CircuitBreakerRegistry circuitBreakerRegistry,
            LogicExecutor executor) {
        super(executor); // 부모 추상 클래스에 executor 전달
        this.redisLockStrategy = redisLockStrategy;
        this.mysqlLockStrategy = mysqlLockStrategy;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisLock");
    }

    /**
     * [Tier 1: Redis] 시도 -> 실패 시 [Tier 2: MySQL]로 복구
     * executor.executeCheckedWithRecovery를 사용하여 try-catch 없이 흐름을 제어합니다.
     */
    @Override
    protected boolean tryLock(String lockKey, long waitTime, long leaseTime) throws Throwable {
        String originalKey = lockKey.replace("lock:", "");
        TaskContext context = TaskContext.of("ResilientLock", "TryLockTier", lockKey);

        return executor.executeCheckedWithRecovery(
                // 1. Redis 락 시도 (서킷 브레이커 보호)
                () -> circuitBreaker.executeCheckedSupplier(() -> {
                    // 🚀 [핵심 수정] tryLockImmediately 대신 executeWithLock을 사용하여 '대기' 기능 활성화
                    // Redis Pub/Sub을 통해 waitTime 동안 락 획득을 대기합니다.
                    // 락 획득 성공 시 true를 반환하는 람다를 실행합니다.
                    return redisLockStrategy.executeWithLock(originalKey, waitTime, leaseTime, () -> true);
                }),

                // 2. Redis 실패 시 MySQL 락으로 복구 (Fallback)
                // - CircuitBreaker OPEN (Redis 다운)
                // - DistributedLockException (Redis 락 획득 타임아웃)
                (e) -> {
                    log.warn("🔴 [Resilient Lock] Redis unavailable (State: {}). Falling back to MySQL: {} | Cause: {}",
                            circuitBreaker.getState(), lockKey, e.getMessage());

                    // 비상시에는 MySQL에서 즉시 시도 (또는 짧은 대기)
                    return mysqlLockStrategy.tryLockImmediately(originalKey, leaseTime);
                },
                context
        );
    }

    /**
     * ✅ [try-catch 제거] executor.executeWithFinally 적용
     * Redis 해제 시도 후, 성공/실패 여부와 상관없이 MySQL 해제를 보장합니다.
     */
    @Override
    protected void unlockInternal(String lockKey) {
        String originalKey = lockKey.replace("lock:", "");
        TaskContext context = TaskContext.of("ResilientLock", "UnlockInternal", lockKey);

        executor.executeWithFinally(
                // Redis 락 해제 시도 (예외 발생 가능)
                () -> {
                    circuitBreaker.executeRunnable(() -> redisLockStrategy.unlock(originalKey));
                    return null;
                },
                // MySQL 락 해제 (finally 블록에서 반드시 실행됨)
                // MySqlNamedLockStrategy의 unlock 로그를 DEBUG로 낮췄으므로 안전함
                () -> mysqlLockStrategy.unlock(originalKey),
                context
        );
    }

    /**
     * ✅ [try-catch 제거] executor.executeOrDefault 적용
     */
    @Override
    public boolean tryLockImmediately(String key, long leaseTime) {
        return executor.executeOrDefault(
                () -> this.tryLock(buildLockKey(key), 0, leaseTime),
                false, // 예외 발생 시 기본적으로 실패(false) 반환
                TaskContext.of("ResilientLock", "TryLockImmediate", key)
        );
    }

    @Override
    protected boolean shouldUnlock(String lockKey) {
        // Redis와 MySQL 중 어느 것이 걸려있는지 확신할 수 없으므로,
        // 항상 unlockInternal(복합 해제 로직)로 진입하도록 설계합니다.
        return true;
    }
}