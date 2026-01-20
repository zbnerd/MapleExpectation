package maple.expectation.global.lock;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.redisson.client.RedisException;
import org.redisson.client.RedisTimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 회복력 있는 락 전략 (Redis 우선, 실패 시 MySQL로 복구)
 *
 * <p>LogicExecutor.executeWithFallback()을 사용하여 단일 파이프라인에서
 * try-catch/throws 없이 예외 필터링을 구현합니다.
 *
 * <p><b>예외 필터링 정책</b>:
 * <ul>
 *   <li>비즈니스 예외 (ClientBaseException): Fallback 없이 즉시 전파</li>
 *   <li>인프라 예외 (Redis/CircuitBreaker): MySQL Fallback 허용</li>
 *   <li>Unknown 예외 (NPE 등): 즉시 전파 (버그 조기 발견)</li>
 *   <li>Checked Throwable: 정책 위반이므로 fail-fast (IllegalStateException)</li>
 * </ul>
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
            LogicExecutor executor
    ) {
        super(executor);
        this.redisLockStrategy = redisLockStrategy;
        this.mysqlLockStrategy = mysqlLockStrategy;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisLock");
    }

    // ========================================
    // 핵심 메서드: executeWithLock Override
    // ========================================

    /**
     * Tiered Lock 실행 (Redis → MySQL Fallback)
     *
     * <p><b>정책</b>:
     * <ul>
     *   <li>Biz(ClientBaseException): fallback 금지, 즉시 전파</li>
     *   <li>Infra(Redis/CB/DistributedLockException): MySQL fallback</li>
     *   <li>Unknown: 즉시 전파(버그 조기 발견)</li>
     * </ul>
     *
     * <p><b>제약</b>:
     * <ul>
     *   <li>호출부 throws 금지</li>
     *   <li>prod 코드 try-catch 금지</li>
     * </ul>
     */
    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) {
        String originalKey = removeLockPrefix(key);
        TaskContext context = TaskContext.of("ResilientLock", "ExecuteWithLock", originalKey);

        return executor.executeWithFallback(
                // Redis tier 전체 실행 (락+task+해제)
                () -> circuitBreaker.executeCheckedSupplier(() ->
                        redisLockStrategy.executeWithLock(originalKey, waitTime, leaseTime, task)
                ),
                // 예외 분기: Function<Throwable, T> (throws 불가, checked는 fail-fast)
                (t) -> handleFallback(
                        t,
                        originalKey,
                        "executeWithLock",
                        () -> mysqlLockStrategy.executeWithLock(originalKey, waitTime, leaseTime, task)
                ),
                context
        );
    }

    /**
     * [Tier 1: Redis] 락 획득만 시도 → 실패 시 [Tier 2: MySQL] 복구
     *
     * <p><b>P1 버그 수정 (PR #157, #154 Codex 지적)</b>:
     * <ul>
     *   <li>Before: executeWithLock(() → true) 사용 → 락 획득 후 즉시 해제됨</li>
     *   <li>After: tryLockImmediately() 사용 → 락 획득만, 해제는 unlockInternal()에서</li>
     * </ul>
     *
     * <p><b>제약 사항</b>: waitTime 파라미터 무시됨 (tryLockImmediately는 즉시 시도)
     *
     * @see <a href="https://github.com/redisson/redisson/wiki/8.-Distributed-locks-and-synchronizers">Redisson Lock Best Practice</a>
     */
    @Override
    protected boolean tryLock(String lockKey, long waitTime, long leaseTime) {
        String originalKey = removeLockPrefix(lockKey);
        TaskContext context = TaskContext.of("ResilientLock", "TryLock", lockKey);

        // P1 Fix: tryLockImmediately() 사용 (락 획득만, 해제 안 함)
        return executor.executeWithFallback(
                () -> circuitBreaker.executeCheckedSupplier(() ->
                        redisLockStrategy.tryLockImmediately(originalKey, leaseTime)
                ),
                (t) -> handleTryLockFallback(t, originalKey, leaseTime),
                context
        );
    }

    /**
     * tryLock 전용 fallback 처리
     *
     * <p>MySQL Named Lock은 세션 기반이므로 tryLockImmediately() 지원 불가.
     * Redis 실패 시 DistributedLockException으로 명확한 실패 전달.
     */
    private boolean handleTryLockFallback(Throwable t, String key, long leaseTime) {
        Throwable cause = unwrap(t);

        // Biz 예외: 즉시 전파
        if (cause instanceof ClientBaseException) {
            throwAsRuntime(cause);
            return false; // unreachable
        }

        // Infra 예외: MySQL fallback 시도 (UnsupportedOperationException 예상)
        if (isInfrastructureException(cause)) {
            log.warn("[TieredLock:tryLock] Redis failed, MySQL fallback 불가 (세션 기반). " +
                    "key={}, state={}, cause={}:{}",
                    key, circuitBreaker.getState(),
                    cause.getClass().getSimpleName(), cause.getMessage());
            // MySQL은 tryLockImmediately 지원 불가 → 락 획득 실패
            throw new DistributedLockException(
                    "Tiered Lock 획득 실패: Redis 불가 + MySQL 세션 기반으로 fallback 불가 [key=" + key + "]",
                    cause
            );
        }

        // Unknown: 즉시 전파
        log.error("[TieredLock:tryLock] Unknown exception. key={}, cause={}:{}",
                key, cause.getClass().getName(), cause.getMessage(), cause);
        throwAsRuntime(cause);
        return false; // unreachable
    }

    // ========================================
    // 예외 필터링 헬퍼 메서드
    // ========================================

    /**
     * 래핑된 예외를 unwrap하여 원본 예외 반환
     */
    private Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if ((cur instanceof CompletionException
                    || cur instanceof ExecutionException
                    || cur instanceof UndeclaredThrowableException)
                    && cur.getCause() != null) {
                cur = cur.getCause();
                continue;
            }
            return cur;
        }
        return t;
    }

    /**
     * 인프라 예외 여부 판별
     */
    private boolean isInfrastructureException(Throwable cause) {
        return cause instanceof DistributedLockException
                || cause instanceof CallNotPermittedException
                || cause instanceof RedisException
                || cause instanceof RedisTimeoutException;
    }

    /**
     * lock: prefix 제거
     */
    private String removeLockPrefix(String lockKey) {
        return lockKey.startsWith("lock:") ? lockKey.substring(5) : lockKey;
    }

    /**
     * fallback 분기 (throws / try-catch 없음)
     *
     * <p><b>NOTE</b>:
     * <ul>
     *   <li>Function&lt;Throwable, T&gt;은 throws 불가</li>
     *   <li>RuntimeException/Error는 그대로 throw</li>
     *   <li>Biz 경계에서 checked Throwable은 정책 위반이므로 fail-fast</li>
     *   <li>mysqlFallback.getUnchecked()로 checked 예외 처리 (인프라 레이어)</li>
     * </ul>
     *
     * @param mysqlFallback ThrowingSupplier - getUnchecked()로 실행
     */
    private <T> T handleFallback(
            Throwable t,
            String key,
            String op,
            ThrowingSupplier<T> mysqlFallback
    ) {
        Throwable cause = unwrap(t);

        // InterruptedException은 Lock 도메인 예외로 정규화
        if (cause instanceof InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new DistributedLockException(
                    "락 획득/실행 중 인터럽트 [op=" + op + ", key=" + key + "]", ie);
        }

        // 1) Biz 예외: fallback 절대 금지
        if (cause instanceof ClientBaseException) {
            throwAsRuntime(cause);
            return null; // unreachable
        }

        // 2) Infra 예외: MySQL fallback (getUnchecked로 checked는 fail-fast)
        if (isInfrastructureException(cause)) {
            log.warn("[TieredLock:{}] Redis failed -> MySQL fallback. key={}, state={}, cause={}:{}",
                    op, key, circuitBreaker.getState(),
                    cause.getClass().getSimpleName(), cause.getMessage());
            return mysqlFallback.getUnchecked();
        }

        // 3) Unknown: 즉시 전파 (버그 조기 발견)
        log.error("[TieredLock:{}] Unknown exception -> propagate. key={}, cause={}:{}",
                op, key, cause.getClass().getName(), cause.getMessage(), cause);
        throwAsRuntime(cause);
        return null; // unreachable
    }

    /**
     * RuntimeException/Error는 원형 전파, checked Throwable은 정책 위반으로 fail-fast
     */
    private static void throwAsRuntime(Throwable t) {
        if (t instanceof Error e) throw e;
        if (t instanceof RuntimeException re) throw re;
        // Biz 경계에서 checked Throwable이 올라오는 것은 설계 위반
        throw new IllegalStateException(
                "Unexpected checked Throwable (policy violation): " + t.getClass().getName(), t);
    }

    // ========================================
    // unlock / immediate
    // ========================================

    @Override
    protected void unlockInternal(String lockKey) {
        String originalKey = removeLockPrefix(lockKey);
        TaskContext context = TaskContext.of("ResilientLock", "UnlockInternal", lockKey);

        executor.executeWithFinally(
                () -> {
                    circuitBreaker.executeRunnable(() -> redisLockStrategy.unlock(originalKey));
                    return null;
                },
                () -> mysqlLockStrategy.unlock(originalKey),
                context
        );
    }

    @Override
    public boolean tryLockImmediately(String key, long leaseTime) {
        return executor.executeOrDefault(
                () -> this.tryLock(buildLockKey(key), 0, leaseTime),
                false,
                TaskContext.of("ResilientLock", "TryLockImmediate", key)
        );
    }

    @Override
    protected boolean shouldUnlock(String lockKey) {
        return true;
    }

    // ========================================
    // [P0-N02] 다중 락 순서 보장 실행
    // ========================================

    /**
     * [P0-N02] 다중 락 순서 보장 실행 (Redis → MySQL Fallback)
     *
     * <p>Coffman Condition #4 (Circular Wait)를 방지하기 위해
     * 키를 알파벳순으로 정렬 후 순차적으로 락을 획득합니다.</p>
     *
     * <p><b>Fallback 정책</b>:
     * <ul>
     *   <li>Redis 우선 시도</li>
     *   <li>Redis 실패 시 MySQL Fallback</li>
     *   <li>비즈니스 예외는 Fallback 없이 즉시 전파</li>
     * </ul>
     *
     * @see OrderedLockExecutor
     */
    @Override
    public <T> T executeWithOrderedLocks(
            List<String> keys,
            long totalTimeout,
            TimeUnit timeUnit,
            long leaseTime,
            ThrowingSupplier<T> task
    ) throws Throwable {
        String keysStr = String.join(",", keys);
        TaskContext context = TaskContext.of("ResilientLock", "OrderedExecute", keysStr);

        return executor.executeWithFallback(
                // Redis tier: 순서 보장 다중 락 실행
                () -> circuitBreaker.executeCheckedSupplier(() ->
                        redisLockStrategy.executeWithOrderedLocks(keys, totalTimeout, timeUnit, leaseTime, task)
                ),
                // MySQL fallback: 순서 보장 다중 락 실행
                (t) -> handleOrderedLockFallback(
                        t,
                        keysStr,
                        () -> mysqlLockStrategy.executeWithOrderedLocks(keys, totalTimeout, timeUnit, leaseTime, task)
                ),
                context
        );
    }

    /**
     * 다중 락 Fallback 처리
     */
    private <T> T handleOrderedLockFallback(
            Throwable t,
            String keys,
            ThrowingSupplier<T> mysqlFallback
    ) {
        Throwable cause = unwrap(t);

        // InterruptedException 처리
        if (cause instanceof InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new DistributedLockException(
                    "다중 락 획득 중 인터럽트 [keys=" + keys + "]", ie);
        }

        // 비즈니스 예외: 즉시 전파
        if (cause instanceof ClientBaseException) {
            throwAsRuntime(cause);
            return null;
        }

        // 인프라 예외: MySQL Fallback
        if (isInfrastructureException(cause)) {
            log.warn("[TieredLock:OrderedExecute] Redis failed -> MySQL fallback. keys={}, state={}, cause={}:{}",
                    keys, circuitBreaker.getState(),
                    cause.getClass().getSimpleName(), cause.getMessage());
            return mysqlFallback.getUnchecked();
        }

        // Unknown: 즉시 전파
        log.error("[TieredLock:OrderedExecute] Unknown exception -> propagate. keys={}, cause={}:{}",
                keys, cause.getClass().getName(), cause.getMessage(), cause);
        throwAsRuntime(cause);
        return null;
    }
}
