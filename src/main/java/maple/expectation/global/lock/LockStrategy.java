package maple.expectation.global.lock;

import maple.expectation.global.common.function.ThrowingSupplier;

import java.util.function.Supplier;

public interface LockStrategy {

    <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) throws Throwable;

    <T> T executeWithLock(String key, ThrowingSupplier<T> task) throws Throwable;
}