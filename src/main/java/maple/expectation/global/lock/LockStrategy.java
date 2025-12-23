package maple.expectation.global.lock;

import java.util.function.Supplier;

public interface LockStrategy {
    <T> T executeWithLock(String key, Supplier<T> task);

    <T> T executeWithLock(String key, long waitTime, long leaseTime, Supplier<T> task);
}