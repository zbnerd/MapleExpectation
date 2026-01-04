package maple.expectation.global.lock;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Slf4j
@Primary
@Component
public class ResilientLockStrategy implements LockStrategy {

    private final LockStrategy redisLockStrategy;
    private final LockStrategy mysqlLockStrategy;
    private final CircuitBreaker circuitBreaker;
    private final LogicExecutor executor;

    public ResilientLockStrategy(
            @Qualifier("redisDistributedLockStrategy") RedisDistributedLockStrategy redisLockStrategy,
            MySqlNamedLockStrategy mysqlLockStrategy,
            CircuitBreakerRegistry circuitBreakerRegistry,
            LogicExecutor executor) {

        this.redisLockStrategy = redisLockStrategy;
        this.mysqlLockStrategy = mysqlLockStrategy;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisLock");
        this.executor = executor;
    }

    @Override
    public <T> T executeWithLock(String key, ThrowingSupplier<T> task) throws Throwable {
        return executeWithLock(key, 10, 20, task);
    }

    /**
     * ✅ [최종 박멸] 중첩 제거 및 Throwable 전파 완벽 대응
     */
    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) throws Throwable {
        TaskContext context = TaskContext.of("ResilientLock", "ExecuteWithLock", key);

        // 🚀 단일 호출로 Tier 1 시도 -> 실패 시 Tier 2 복구 -> 최종 Throwable 전파
        return executor.executeCheckedWithRecovery(
                () -> performRedisLock(key, waitTime, leaseTime, task),
                (e) -> performMysqlFallback(key, waitTime, leaseTime, task),
                context
        );
    }

    private <T> T performRedisLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) throws Throwable {
        return circuitBreaker.executeCheckedSupplier(() -> {
            log.debug("🔵 [Resilient Lock] Attempting Redis lock: {}", key);
            return redisLockStrategy.executeWithLock(key, waitTime, leaseTime, task);
        });
    }

    private <T> T performMysqlFallback(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) throws Throwable {
        log.warn("🔴 [Resilient Lock] Redis failed (State: {}). Falling back to MySQL: {}",
                circuitBreaker.getState(), key);
        return mysqlLockStrategy.executeWithLock(key, waitTime, leaseTime, task);
    }

    @Override
    public boolean tryLockImmediately(String key, long leaseTime) {
        return executor.executeWithRecovery(
                () -> circuitBreaker.executeSupplier(() -> redisLockStrategy.tryLockImmediately(key, leaseTime)),
                (e) -> mysqlLockStrategy.tryLockImmediately(key, leaseTime),
                TaskContext.of("ResilientLock", "TryLock", key)
        );
    }

    @Override
    public void unlock(String key) {
        executor.executeWithRecovery(
                () -> {
                    circuitBreaker.executeRunnable(() -> redisLockStrategy.unlock(key));
                    return null;
                },
                (e) -> {
                    mysqlLockStrategy.unlock(key);
                    return null;
                },
                TaskContext.of("ResilientLock", "Unlock", key)
        );
    }
}