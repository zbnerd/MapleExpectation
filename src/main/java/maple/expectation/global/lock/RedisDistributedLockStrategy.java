package maple.expectation.global.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Component
@Primary // 분산 환경이므로 Redis 기반 락을 우선적으로 사용합니다.
@RequiredArgsConstructor
public class RedisDistributedLockStrategy implements LockStrategy {

    private final RedissonClient redissonClient;

    @Override
    public <T> T executeWithLock(String key, Supplier<T> task) {
        RLock lock = redissonClient.getLock("lock:" + key);
        
        try {
            // 스케줄러용: 락을 얻기 위해 대기하지 않음(0), 락 점유 시간은 10초
            // 만약 락을 획득하지 못하면 즉시 null 혹은 예외를 반환하도록 설계
            boolean isLocked = lock.tryLock(0, 10, TimeUnit.SECONDS);

            if (!isLocked) {
                log.debug("⏭️ [Distributed Lock] '{}' 획득 실패. 다른 인스턴스가 실행 중입니다.", key);
                return null; 
            }

            try {
                log.info("🔓 [Distributed Lock] '{}' 획득 성공.", key);
                return task.get();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    log.info("🔒 [Distributed Lock] '{}' 해제 완료.", key);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("락 획득 중 인터럽트 발생", e);
        }
    }
}