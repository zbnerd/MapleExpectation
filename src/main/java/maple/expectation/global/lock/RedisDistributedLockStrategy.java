package maple.expectation.global.lock;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis 분산 락 전략 (Redisson 기반)
 *
 * <p>AbstractLockStrategy를 상속하여 85% 이상의 보일러플레이트 코드를 제거했습니다.
 *
 * <h3>Before (70줄)</h3>
 * - try-catch-finally 중복
 * - 로그 중복
 * - InterruptedException 처리 중복
 *
 * <h3>After (25줄)</h3>
 * - 핵심 로직만 구현
 * - throws Throwable 제거
 * - 코드 평탄화 완료
 *
 * @see AbstractLockStrategy
 * @since 1.0.0
 */
@Slf4j
@Component
@Qualifier("redisDistributedLockStrategy")
@Profile("!test")
public class RedisDistributedLockStrategy extends AbstractLockStrategy {

    private final RedissonClient redissonClient;

    public RedisDistributedLockStrategy(RedissonClient redissonClient, LogicExecutor executor) {
        super(executor);
        this.redissonClient = redissonClient;
    }

    @Override
    protected boolean tryLock(String lockKey, long waitTime, long leaseTime) throws Throwable {
        RLock lock = redissonClient.getLock(lockKey);
        return lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
    }

    @Override
    protected void unlockInternal(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        lock.unlock();
    }

    @Override
    protected boolean shouldUnlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        return lock.isHeldByCurrentThread();
    }

    @Override
    public boolean tryLockImmediately(String key, long leaseTime) {
        String lockKey = buildLockKey(key);

        return executor.executeOrDefault(
            () -> this.attemptImmediateLock(lockKey, leaseTime),
            false,
            "tryLockImmediately:" + key
        );
    }

    /**
     * 즉시 락 획득 시도 (평탄화: 별도 메서드로 분리)
     */
    private boolean attemptImmediateLock(String lockKey, long leaseTime) throws Throwable {
        boolean isLocked = tryLock(lockKey, 0, leaseTime);

        if (isLocked) {
            log.debug("🔓 [Distributed Lock] '{}' 즉시 획득 성공.", lockKey);
        } else {
            log.debug("⏭️ [Distributed Lock] '{}' 즉시 획득 실패 (이미 점유됨).", lockKey);
        }

        return isLocked;
    }
}