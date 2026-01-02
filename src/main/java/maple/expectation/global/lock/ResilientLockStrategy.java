package maple.expectation.global.lock;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Resilient Lock Strategy - Tiered Locking 구현
 *
 * [Tiered Locking 플로우]
 * Tier 1 (Primary)  : Redis Distributed Lock (Circuit Breaker로 보호)
 * Tier 2 (Fallback) : MySQL Named Lock (Redis 장애 시)
 *
 * [Circuit Breaker 동작]
 * - CLOSED: Redis 정상 → Redis 락 사용
 * - OPEN:   Redis 장애 감지 → MySQL 락으로 Fast-Fail (Redis 시도 없이 바로 MySQL)
 * - HALF_OPEN: Redis 복구 확인 중 → 제한적으로 Redis 시도
 *
 * [중요 설계]
 * - ✅ @Primary 설정: LockAspect에서 자동으로 이 전략 선택 (체크포인트 2)
 * - Circuit Breaker는 Redis 장애만 감지 (MySQL 장애는 별도 처리)
 * - 모든 예외는 상위로 전파 (최종적으로 LockAspect에서 처리)
 */
@Slf4j
@Primary  // ✅ 체크포인트 2: AOP에서 자동으로 회복력 있는 전략 사용
@Component
@Profile("!test")
public class ResilientLockStrategy implements LockStrategy {

    private final LockStrategy redisLockStrategy;
    private final LockStrategy mysqlLockStrategy;
    private final CircuitBreaker circuitBreaker;

    /**
     * 생성자 주입
     * - redisLockStrategy: @Qualifier로 명시적 주입
     * - mysqlLockStrategy: 타입 기반 자동 주입
     * - circuitBreaker: CircuitBreakerRegistry에서 "redisLock" 이름으로 조회
     */
    public ResilientLockStrategy(
            @Qualifier("redisDistributedLockStrategy") RedisDistributedLockStrategy redisLockStrategy,
            MySqlNamedLockStrategy mysqlLockStrategy,
            CircuitBreakerRegistry circuitBreakerRegistry) {

        this.redisLockStrategy = redisLockStrategy;
        this.mysqlLockStrategy = mysqlLockStrategy;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisLock");

        log.info("✅ [Resilient Lock] Initialized with Redis (primary) + MySQL (fallback)");
    }

    @Override
    public <T> T executeWithLock(String key, ThrowingSupplier<T> task) throws Throwable {
        return executeWithLock(key, 10, 20, task);
    }

    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) throws Throwable {
        try {
            // 🔵 Tier 1: Redis 락 시도 (Circuit Breaker로 보호)
            return circuitBreaker.executeCheckedSupplier(() -> {
                log.debug("🔵 [Resilient Lock] Attempting Redis lock for: {}", key);
                return redisLockStrategy.executeWithLock(key, waitTime, leaseTime, task);
            });

        } catch (Exception redisException) {
            // 🔴 Redis 실패 또는 Circuit Breaker OPEN
            CircuitBreaker.State cbState = circuitBreaker.getState();
            log.warn("🔴 [Resilient Lock] Redis unavailable for '{}'. CB State: {}, Reason: {}. Falling back to MySQL...",
                key, cbState, redisException.getMessage());

            try {
                // 🟡 Tier 2: MySQL Named Lock Fallback
                return mysqlLockStrategy.executeWithLock(key, waitTime, leaseTime, task);

            } catch (Exception mysqlException) {
                // ❌ 양쪽 모두 실패
                log.error("❌ [Resilient Lock] Both Redis and MySQL locks failed for: {}", key);
                log.error("   - Redis error: {}", redisException.getMessage());
                log.error("   - MySQL error: {}", mysqlException.getMessage());

                // MySQL 예외를 상위로 전파 (최종 실패)
                throw mysqlException;
            }
        }
    }
}
