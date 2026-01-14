package maple.expectation.global.lock;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class RedisDistributedLockStrategy extends AbstractLockStrategy {

    private final RedissonClient redissonClient;

    public RedisDistributedLockStrategy(RedissonClient redissonClient, LogicExecutor executor) {
        super(executor);
        this.redissonClient = redissonClient;
    }

    /**
     * Redisson Watchdog 모드로 락 획득 (CLAUDE.md 섹션 17 준수)
     *
     * <p>leaseTime 파라미터를 의도적으로 무시하고 Watchdog 모드를 사용합니다.
     * Watchdog은 lockWatchdogTimeout(기본 30초)마다 락을 자동 갱신하여
     * 작업 시간이 예상보다 길어져도 락이 조기 해제되지 않습니다.
     *
     * @param lockKey 락 키
     * @param waitTime 락 획득 대기 시간 (초)
     * @param leaseTime 무시됨 (Watchdog 모드 사용)
     * @return 락 획득 성공 여부
     */
    @Override
    protected boolean tryLock(String lockKey, long waitTime, long leaseTime) throws Throwable {
        RLock lock = redissonClient.getLock(lockKey);
        // ✅ Watchdog 모드: leaseTime 생략 → 30초마다 자동 갱신
        // ❌ 이전: lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS) → 작업 초과 시 락 해제됨
        return lock.tryLock(waitTime, TimeUnit.SECONDS);
    }

    @Override
    protected void unlockInternal(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    @Override
    protected boolean shouldUnlock(String lockKey) {
        return redissonClient.getLock(lockKey).isHeldByCurrentThread();
    }

    @Override
    public boolean tryLockImmediately(String key, long leaseTime) {
        String lockKey = buildLockKey(key);

        return executor.executeOrDefault(
                () -> this.attemptImmediateLock(lockKey, leaseTime),
                false,
                TaskContext.of("Lock", "RedisTryImmediate", key) // ✅ TaskContext 적용
        );
    }

    private boolean attemptImmediateLock(String lockKey, long leaseTime) throws Throwable {
        return tryLock(lockKey, 0, leaseTime);
    }
}