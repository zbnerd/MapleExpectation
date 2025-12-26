package maple.expectation.global.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.DistributedLockException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class RedisDistributedLockStrategy implements LockStrategy {

    private final RedissonClient redissonClient;

    // 인터페이스 기본 구현: 비즈니스 로직(후원 등)을 위해 3초간 대기하도록 설정
    @Override
    public <T> T executeWithLock(String key, Supplier<T> task) {
        return executeWithLock(key, 3, 10, task); // 기본 waitTime 3초 부여
    }

    // 오버로딩: 대기 시간을 직접 조절해야 하는 경우 (스케줄러 등) 사용
    public <T> T executeWithLock(String key, long waitTime, long leaseTime, Supplier<T> task) {
        RLock lock = redissonClient.getLock("lock:" + key);

        try {
            // tryLock(대기시간, 점유시간, 단위)
            boolean isLocked = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);

            if (!isLocked) {
                log.warn("⏭️ [Distributed Lock] '{}' 획득 실패. 경합 중이거나 작업 진행 중.", key);
                // 💡 핵심: null이 아닌 예외를 던져서 테스트의 catch 블록이 작동하게 함
                throw new DistributedLockException("락 획득 타임아웃: " + key);
            }

            try {
                log.debug("🔓 [Distributed Lock] '{}' 획득 성공.", key);
                return task.get();
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