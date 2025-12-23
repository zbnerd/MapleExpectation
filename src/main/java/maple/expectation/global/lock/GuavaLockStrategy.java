package maple.expectation.global.lock;

import com.google.common.util.concurrent.Striped;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.DistributedLockException;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

@Slf4j
@Component
public class GuavaLockStrategy implements LockStrategy {
    private final Striped<Lock> locks = Striped.lock(128);

    @Override
    public <T> T executeWithLock(String key, Supplier<T> task) {
        // 기본값: 무한정 대기 (기존 로직 유지)
        return executeWithLock(key, Long.MAX_VALUE, -1, task);
    }

    @Override
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, Supplier<T> task) {
        Lock lock = locks.get(key);
        try {
            // 💡 waitTime 동안 락 획득 시도
            boolean isLocked = lock.tryLock(waitTime, TimeUnit.SECONDS);

            if (!isLocked) {
                log.warn("⏭️ [Guava Lock] '{}' 획득 실패. 타임아웃 발생.", key);
                // 💡 Redis와 동일하게 예외를 던져서 테스트의 catch 블록이 작동하게 함
                throw new DistributedLockException("로컬 락 획득 실패: " + key);
            }

            try {
                return task.get();
            } finally {
                // 💡 로컬 락은 leaseTime(자동 해제)이 없으므로 직접 해제 필수
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DistributedLockException("로컬 락 시도 중 인터럽트 발생");
        }
    }
}