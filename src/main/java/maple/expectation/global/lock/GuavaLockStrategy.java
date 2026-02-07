package maple.expectation.global.lock;

import com.google.common.util.concurrent.Striped;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Guava Striped Lock 전략 (테스트 환경 전용 - 100% 평탄화 완료) */
@Slf4j
@Component
@Profile("test")
public class GuavaLockStrategy extends AbstractLockStrategy {

  private final Striped<Lock> locks = Striped.lock(128);

  public GuavaLockStrategy(LogicExecutor executor) {
    super(executor);
  }

  @Override
  protected boolean tryLock(String lockKey, long waitTime, long leaseTime) throws Throwable {
    // 부모 클래스(AbstractLockStrategy)의 템플릿 메서드에서 호출됨
    return locks.get(lockKey).tryLock(waitTime, TimeUnit.SECONDS);
  }

  @Override
  protected void unlockInternal(String lockKey) {
    locks.get(lockKey).unlock();
  }

  @Override
  protected boolean shouldUnlock(String lockKey) {
    // 로컬 락은 상태 체크 대신 해제 시도 시 발생하는 예외를 Executor가 처리하도록 위임
    return true;
  }

  @Override
  protected String buildLockKey(String key) {
    return key;
  }

  @Override
  public boolean tryLockImmediately(String key, long leaseTime) {
    // [패턴 3] executeOrDefault를 사용하여 try-catch 없이 즉시 획득 시도
    return executor.executeOrDefault(
        () -> locks.get(key).tryLock(), false, TaskContext.of("Lock", "GuavaTryImmediate", key));
  }

  /** ✅ 박멸 완료: try-catch를 제거하고 LogicExecutor의 executeVoid로 대체 */
  private void performSafeUnlock(String key) {
    TaskContext context = TaskContext.of("Lock", "GuavaSafeUnlock", key);

    executor.executeVoid(
        () -> {
          locks.get(key).unlock();
          log.trace("[Guava Lock] '{}' 해제 완료", key);
        },
        context);
  }
}
