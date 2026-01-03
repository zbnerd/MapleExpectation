package maple.expectation.global.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.strategy.ExceptionTranslator;

/**
 * 락 전략 추상 클래스
 *
 * <p>Template Method Pattern을 사용하여 중복 제거:
 * <ul>
 *   <li>{@link #executeWithLock}: 템플릿 메서드 (변하지 않는 뼈대)</li>
 *   <li>{@link #tryLock}, {@link #unlock}: 추상 메서드 (변하는 부분)</li>
 *   <li>{@link #onLockAcquired}, {@link #onLockFailed}: Hook 메서드 (선택적 확장)</li>
 * </ul>
 *
 * <h3>코드 평탄화 적용</h3>
 * <p>기존 85%의 중복 코드를 제거하고, 각 구현체는 핵심 로직만 구현합니다.
 *
 * <h3>Before (RedisDistributedLockStrategy, 70줄)</h3>
 * <pre>{@code
 * public <T> T executeWithLock(...) throws Throwable {
 *     RLock lock = redissonClient.getLock("lock:" + key);
 *     try {
 *         boolean isLocked = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
 *         if (!isLocked) {
 *             log.warn("⏭️ [Distributed Lock] '{}' 획득 실패.", key);
 *             throw new DistributedLockException("락 획득 타임아웃: " + key);
 *         }
 *         try {
 *             log.debug("🔓 [Distributed Lock] '{}' 획득 성공.", key);
 *             return task.get();
 *         } finally {
 *             if (lock.isHeldByCurrentThread()) {
 *                 lock.unlock();
 *                 log.debug("🔒 [Distributed Lock] '{}' 해제 완료.", key);
 *             }
 *         }
 *     } catch (InterruptedException e) {
 *         Thread.currentThread().interrupt();
 *         throw new DistributedLockException("락 시도 중 인터럽트 발생");
 *     }
 * }
 * }</pre>
 *
 * <h3>After (RedisDistributedLockStrategy, 25줄)</h3>
 * <pre>{@code
 * protected boolean tryLock(String lockKey, long wait, long lease) throws Throwable {
 *     RLock lock = redissonClient.getLock(lockKey);
 *     return lock.tryLock(wait, lease, TimeUnit.SECONDS);
 * }
 *
 * protected void unlock(String lockKey) {
 *     redissonClient.getLock(lockKey).unlock();
 * }
 *
 * protected boolean shouldUnlock(String lockKey) {
 *     return redissonClient.getLock(lockKey).isHeldByCurrentThread();
 * }
 * }</pre>
 *
 * @see LockStrategy
 * @see LogicExecutor
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractLockStrategy implements LockStrategy {

    protected final LogicExecutor executor;

    /**
     * 템플릿 메서드: 락 획득 → 작업 실행 → 락 해제
     *
     * <p>코드 평탄화를 위해 LogicExecutor를 활용합니다.
     */
    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) {
        String lockKey = buildLockKey(key);

        return executor.executeWithTranslation(
            () -> this.performLockAndExecute(lockKey, waitTime, leaseTime, task),
            ExceptionTranslator.forLock(),
            "executeWithLock:" + key
        );
    }

    @Override
    public <T> T executeWithLock(String key, ThrowingSupplier<T> task) throws Throwable {
        return executeWithLock(key, 10, 20, task);
    }

    @Override
    public void unlock(String key) {
        String lockKey = buildLockKey(key);

        executor.executeVoid(
            () -> this.performUnlock(lockKey),
            "unlock:" + key
        );
    }

    /**
     * 실제 락 획득 및 작업 실행 로직 (평탄화)
     *
     * <p>비즈니스 로직을 별도 메서드로 분리하여 가독성을 향상시킵니다.
     */
    private <T> T performLockAndExecute(
        String lockKey,
        long waitTime,
        long leaseTime,
        ThrowingSupplier<T> task
    ) throws Throwable {
        // 1. 락 획득 시도
        boolean isLocked = tryLock(lockKey, waitTime, leaseTime);

        if (!isLocked) {
            onLockFailed(lockKey);
            throw createLockFailureException(lockKey);
        }

        // 2. 락 획득 성공 Hook
        onLockAcquired(lockKey);

        // 3. 작업 실행 + finally 블록에서 락 해제
        return executor.executeWithFinally(
            task,
            () -> this.performUnlock(lockKey),
            "lockedTask:" + lockKey
        );
    }

    /**
     * 락 해제 로직 (평탄화)
     *
     * <p>예외 발생 시에도 안전하게 처리합니다.
     */
    private void performUnlock(String lockKey) {
        try {
            if (shouldUnlock(lockKey)) {
                unlockInternal(lockKey);
                onLockReleased(lockKey);
            }
        } catch (Exception e) {
            log.error("락 해제 중 예외 발생: {}", lockKey, e);
        }
    }

    // ===== 추상 메서드 (구현체가 반드시 구현) =====

    /**
     * 락 획득 시도
     *
     * @param lockKey 락 키
     * @param waitTime 대기 시간 (초)
     * @param leaseTime 임대 시간 (초)
     * @return 락 획득 성공 여부
     * @throws Throwable 락 획득 중 발생한 예외
     */
    protected abstract boolean tryLock(String lockKey, long waitTime, long leaseTime) throws Throwable;

    /**
     * 락 해제 (내부용)
     *
     * @param lockKey 락 키
     */
    protected abstract void unlockInternal(String lockKey);

    /**
     * 락 해제 가능 여부 확인 (현재 스레드가 소유하고 있는지 등)
     *
     * @param lockKey 락 키
     * @return 해제 가능 여부
     */
    protected abstract boolean shouldUnlock(String lockKey);

    // ===== Hook 메서드 (구현체가 선택적으로 오버라이드) =====

    /**
     * 락 키 생성 전략 (기본: "lock:" 접두사)
     *
     * @param key 원본 키
     * @return 락 키
     */
    protected String buildLockKey(String key) {
        return "lock:" + key;
    }

    /**
     * 락 획득 성공 시 호출되는 Hook
     *
     * @param lockKey 락 키
     */
    protected void onLockAcquired(String lockKey) {
        log.debug("🔓 [Lock] '{}' 획득 성공", lockKey);
    }

    /**
     * 락 획득 실패 시 호출되는 Hook
     *
     * @param lockKey 락 키
     */
    protected void onLockFailed(String lockKey) {
        log.warn("⏭️ [Lock] '{}' 획득 실패", lockKey);
    }

    /**
     * 락 해제 성공 시 호출되는 Hook
     *
     * @param lockKey 락 키
     */
    protected void onLockReleased(String lockKey) {
        log.debug("🔒 [Lock] '{}' 해제 완료", lockKey);
    }

    /**
     * 락 획득 실패 예외 생성
     *
     * @param lockKey 락 키
     * @return DistributedLockException
     */
    protected RuntimeException createLockFailureException(String lockKey) {
        return new DistributedLockException(lockKey);
    }
}
