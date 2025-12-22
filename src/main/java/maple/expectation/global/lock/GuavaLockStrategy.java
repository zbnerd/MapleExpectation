package maple.expectation.global.lock;

import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Component;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

@Component
public class GuavaLockStrategy implements LockStrategy {
    private final Striped<Lock> locks = Striped.lock(128); // 고정된 락 풀 사용

    @Override
    public <T> T executeWithLock(String key, Supplier<T> task) {
        Lock lock = locks.get(key);
        lock.lock();
        try {
            return task.get();
        } finally {
            lock.unlock();
        }
    }
}