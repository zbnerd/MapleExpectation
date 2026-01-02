package maple.expectation.global.lock;

import maple.expectation.global.common.function.ThrowingSupplier;

public interface LockStrategy {

    // 1. 기존: 락을 획득하고 작업을 실행 (WaitTime 대기 포함)
    <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) throws Throwable;

    // 2. 기존: 기본 설정값으로 락 실행
    <T> T executeWithLock(String key, ThrowingSupplier<T> task) throws Throwable;

    // 3. 🚀 추가: 즉시 락 획득 시도 (기다리지 않고 성공 여부만 반환)
    boolean tryLockImmediately(String key, long leaseTime);

    // 4. 🚀 추가: 락 수동 해제
    void unlock(String key);
}