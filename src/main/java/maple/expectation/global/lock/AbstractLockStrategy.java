package maple.expectation.global.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.strategy.ExceptionTranslator;

/**
 * 락 전략 추상 클래스 (TaskContext 및 평탄화 적용)
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractLockStrategy implements LockStrategy {

    protected final LogicExecutor executor;

    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) {
        String lockKey = buildLockKey(key);
        // ✅ TaskContext 정의: Component="Lock", Operation="Execute"
        TaskContext context = TaskContext.of("Lock", "Execute", key);

        return executor.executeWithTranslation(
                () -> this.performLockAndExecute(lockKey, waitTime, leaseTime, task, context),
                ExceptionTranslator.forLock(),
                context
        );
    }

    @Override
    public <T> T executeWithLock(String key, ThrowingSupplier<T> task) { // ✅ throws Throwable 제거
        return executeWithLock(key, 10, 20, task);
    }

    @Override
    public void unlock(String key) {
        String lockKey = buildLockKey(key);
        // ✅ TaskContext 정의: Component="Lock", Operation="Unlock"
        TaskContext context = TaskContext.of("Lock", "Unlock", key);

        executor.executeVoid(
                () -> this.performUnlock(lockKey, context),
                context
        );
    }

    /**
     * 실제 락 획득 및 작업 실행 로직
     * * <p>private 메서드이므로 throws Throwable을 유지하여 executor가 예외를 가로챌 수 있게 합니다.
     */
    private <T> T performLockAndExecute(
            String lockKey,
            long waitTime,
            long leaseTime,
            ThrowingSupplier<T> task,
            TaskContext context
    ) throws Throwable {
        // 1. 락 획득 시도
        if (!tryLock(lockKey, waitTime, leaseTime)) {
            onLockFailed(lockKey);
            throw createLockFailureException(lockKey);
        }

        // 2. 락 획득 성공 Hook
        onLockAcquired(lockKey);

        // 3. 작업 실행 + finally 블록에서 락 해제
        // ✅ TaskContext 재사용
        return executor.executeWithFinally(
                task,
                () -> this.performUnlock(lockKey, context),
                context
        );
    }

    /**
     * 락 해제 로직 (평탄화 및 노이즈 제거)
     */
    private void performUnlock(String lockKey, TaskContext context) {
        // 락 해제 중의 예외는 로직에 지장을 주지 않도록 executeOrDefault 또는 executeVoid로 보호
        executor.executeVoid(() -> {
            if (shouldUnlock(lockKey)) {
                unlockInternal(lockKey);
                onLockReleased(lockKey);
            }
        }, context);
    }

    // ===== 추상 메서드 및 Hook 메서드는 기존과 동일하게 유지 =====

    protected abstract boolean tryLock(String lockKey, long waitTime, long leaseTime) throws Throwable;
    protected abstract void unlockInternal(String lockKey);
    protected abstract boolean shouldUnlock(String lockKey);

    protected String buildLockKey(String key) {
        return "lock:" + key;
    }

    protected void onLockAcquired(String lockKey) {
        log.debug("🔓 [Lock] '{}' 획득 성공", lockKey);
    }

    protected void onLockFailed(String lockKey) {
        log.warn("⏭️ [Lock] '{}' 획득 실패", lockKey);
    }

    protected void onLockReleased(String lockKey) {
        log.debug("🔒 [Lock] '{}' 해제 완료", lockKey);
    }

    protected RuntimeException createLockFailureException(String lockKey) {
        return new DistributedLockException(lockKey);
    }
}