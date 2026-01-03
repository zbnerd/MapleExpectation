package maple.expectation.global.lock;

import com.google.common.util.concurrent.Striped;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * Guava Striped Lock 전략 (테스트 환경 전용)
 *
 * <p>AbstractLockStrategy를 상속하여 보일러플레이트 코드를 제거했습니다.
 *
 * <h3>특징</h3>
 * <ul>
 *   <li>128개의 락을 조각내어 경합을 최소화하는 구조</li>
 *   <li>테스트 환경에서만 사용 (외부 의존성 없이 빠른 테스트 실행)</li>
 *   <li>leaseTime(TTL)을 지원하지 않음</li>
 * </ul>
 *
 * @see AbstractLockStrategy
 * @since 1.0.0
 */
@Slf4j
@Component
@Profile("test")
public class GuavaLockStrategy extends AbstractLockStrategy {

    // 128개의 락을 조각내어 경합을 최소화하는 구조
    private final Striped<Lock> locks = Striped.lock(128);

    public GuavaLockStrategy(LogicExecutor executor) {
        super(executor);
    }

    @Override
    protected boolean tryLock(String lockKey, long waitTime, long leaseTime) throws Throwable {
        Lock lock = locks.get(lockKey);
        return lock.tryLock(waitTime, TimeUnit.SECONDS);
    }

    @Override
    protected void unlockInternal(String lockKey) {
        Lock lock = locks.get(lockKey);
        lock.unlock();
    }

    @Override
    protected boolean shouldUnlock(String lockKey) {
        // Guava Striped Lock은 항상 해제 시도
        return true;
    }

    @Override
    protected String buildLockKey(String key) {
        // Guava는 "lock:" 접두사 없이 원본 키 사용
        return key;
    }

    @Override
    public boolean tryLockImmediately(String key, long leaseTime) {
        // 로컬 락은 leaseTime(TTL)을 지원하지 않으므로 무시하고 즉시 획득 시도
        return locks.get(key).tryLock();
    }

    /**
     * 안전한 락 해제 (평탄화: 별도 메서드로 분리)
     */
    private void performSafeUnlock(String key) {
        try {
            locks.get(key).unlock();
        } catch (IllegalMonitorStateException e) {
            // 이미 풀렸거나 소유하고 있지 않은 경우 무시
            log.trace("[Guava Lock] '{}' 이미 해제되었거나 소유주가 아님.", key);
        }
    }
}