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
    private final Striped<Lock> locks = Striped.lock(128);

    @Override
    public <T> T executeWithLock(String key, ThrowingSupplier<T> task) throws Throwable {
        return executeWithLock(key, Long.MAX_VALUE, -1, task);
    }

    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) throws Throwable {
        Lock lock = locks.get(key);
        try {
            boolean isLocked = lock.tryLock(waitTime, TimeUnit.SECONDS);

            if (!isLocked) {
                log.warn("⏭️ [Guava Lock] '{}' 획득 실패.", key);
                throw new DistributedLockException("로컬 락 획득 실패: " + key);
            }

            try {
                return task.get(); // ✅ ThrowingSupplier의 예외를 그대로 전파
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DistributedLockException("로컬 락 시도 중 인터럽트 발생");
        }
    }
}