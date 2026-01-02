package maple.expectation.global.lock;

import com.google.common.util.concurrent.Striped;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

@Slf4j
@Component
@Profile("test")
public class GuavaLockStrategy implements LockStrategy {

    // 128개의 락을 조각내어 경합을 최소화하는 구조
    private final Striped<Lock> locks = Striped.lock(128);

    @Override
    public <T> T executeWithLock(String key, ThrowingSupplier<T> task) throws Throwable {
        return executeWithLock(key, 10, -1, task);
    }

    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) throws Throwable {
        Lock lock = locks.get(key);
        try {
            boolean isLocked = lock.tryLock(waitTime, TimeUnit.SECONDS);

            if (!isLocked) {
                log.warn("⏭️ [Guava Lock] '{}' 획득 실패.", key);
                throw new DistributedLockException(key);
            }

            try {
                return task.get();
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DistributedLockException(key, e);
        }
    }

    @Override
    public boolean tryLockImmediately(String key, long leaseTime) {
        // 로컬 락은 leaseTime(TTL)을 지원하지 않으므로 무시하고 즉시 획득 시도
        return locks.get(key).tryLock();
    }

    @Override
    public void unlock(String key) {
        try {
            locks.get(key).unlock();
        } catch (IllegalMonitorStateException e) {
            // 이미 풀렸거나 소유하고 있지 않은 경우 무시
            log.trace("[Guava Lock] '{}' 이미 해제되었거나 소유주가 아님.", key);
        }
    }
}