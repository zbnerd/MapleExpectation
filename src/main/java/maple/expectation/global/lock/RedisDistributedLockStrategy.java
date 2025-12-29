package maple.expectation.global.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@Primary
@Profile("!test")
@RequiredArgsConstructor
public class RedisDistributedLockStrategy implements LockStrategy {

    private final RedissonClient redissonClient;

    @Override
    public <T> T executeWithLock(String key, ThrowingSupplier<T> task) throws Throwable {
        return executeWithLock(key, 3, 15, task);
    }

    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) throws Throwable {
        RLock lock = redissonClient.getLock("lock:" + key);

        try {
            boolean isLocked = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);

            if (!isLocked) {
                log.warn("⏭️ [Distributed Lock] '{}' 획득 실패.", key);
                throw new DistributedLockException(key);
            }

            try {
                log.debug("🔓 [Distributed Lock] '{}' 획득 성공.", key);
                return task.get(); // 🔴 비즈니스 로직 실행 (여기서 404 발생 가능)
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    log.debug("🔒 [Distributed Lock] '{}' 해제 완료.", key);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DistributedLockException(key, e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new DistributedLockException(key, e);
        } catch (Throwable e) {
            throw e;
        }
    }

    @Override
    public boolean tryLockImmediately(String key, long leaseTime) {
        RLock lock = redissonClient.getLock("lock:" + key);
        try {
            return lock.tryLock(0, leaseTime, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void unlock(String key) {
        RLock lock = redissonClient.getLock("lock:" + key);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}