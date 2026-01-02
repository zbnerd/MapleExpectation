package maple.expectation.global.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@Qualifier("redisDistributedLockStrategy")
@Profile("!test")
@RequiredArgsConstructor
public class RedisDistributedLockStrategy implements LockStrategy {

    private final RedissonClient redissonClient;

    @Override
    public <T> T executeWithLock(String key, ThrowingSupplier<T> task) throws Throwable {
        return executeWithLock(key, 10, 20, task);
    }

    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, ThrowingSupplier<T> task) throws Throwable {
        RLock lock = redissonClient.getLock("lock:" + key);

        try {
            boolean isLocked = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);

            if (!isLocked) {
                log.warn("⏭️ [Distributed Lock] '{}' 획득 실패.", key);
                throw new DistributedLockException("락 획득 타임아웃: " + key);
            }

            try {
                log.debug("🔓 [Distributed Lock] '{}' 획득 성공.", key);
                return task.get(); // ✅ 이제 예외를 여기서 감싸지 않고 그대로 던집니다.
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    log.debug("🔒 [Distributed Lock] '{}' 해제 완료.", key);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DistributedLockException("락 시도 중 인터럽트 발생");
        }
    }
}