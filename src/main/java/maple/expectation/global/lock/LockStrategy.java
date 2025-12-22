package maple.expectation.global.lock;

import java.util.function.Supplier;

public interface LockStrategy {
    <T> T executeWithLock(String key, Supplier<T> task);
}