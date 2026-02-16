package maple.expectation.infrastructure.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.error.exception.DistributedLockException;
import maple.expectation.error.exception.InternalSystemException;
import maple.expectation.infrastructure.aop.annotation.Locked;
import maple.expectation.infrastructure.aop.util.CustomSpelParser;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.lock.LockStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Order(0)
@Component
@RequiredArgsConstructor
public class LockAspect {

  private final LockStrategy lockStrategy;
  private final LogicExecutor executor;
  private final CustomSpelParser spelParser;

  @Around("@annotation(locked)")
  public Object applyLock(ProceedingJoinPoint joinPoint, Locked locked) {
    String key = getDynamicKey(joinPoint, locked.key());
    long waitSeconds = locked.timeUnit().toSeconds(locked.waitTime());
    long leaseSeconds = locked.timeUnit().toSeconds(locked.leaseTime());

    // ✅ TaskContext 적용: Component="Lock", Operation="Apply"
    return executor.executeOrCatch(
        () -> this.executeLockProtectedTask(joinPoint, key, waitSeconds, leaseSeconds),
        e -> this.handleLockFailure(joinPoint, key, e),
        TaskContext.of("Lock", "Apply", key));
  }

  private Object executeLockProtectedTask(
      ProceedingJoinPoint joinPoint, String key, long waitSeconds, long leaseSeconds)
      throws Throwable {
    return lockStrategy.executeWithLock(
        key, waitSeconds, leaseSeconds, this.createLockedTask(joinPoint, key));
  }

  private ThrowingSupplier<Object> createLockedTask(ProceedingJoinPoint joinPoint, String key) {
    return () -> {
      log.debug("🔑 [Locked Aspect] 락 획득 성공: {}", key);
      return joinPoint.proceed();
    };
  }

  private Object handleLockFailure(ProceedingJoinPoint joinPoint, String key, Throwable e) {
    if (e instanceof DistributedLockException) {
      log.warn("⏭️ [Locked Timeout] {} - 락 획득 실패. 직접 조회를 시도합니다.", key);
      return proceedWithoutLock(joinPoint, key);
    }
    throw new InternalSystemException("DistributedLockExecution:" + key, e);
  }

  private Object proceedWithoutLock(ProceedingJoinPoint joinPoint, String key) {
    // ✅ TaskContext 적용: Component="Lock", Operation="Fallback"
    return executor.execute(joinPoint::proceed, TaskContext.of("Lock", "Fallback", key));
  }

  private String getDynamicKey(ProceedingJoinPoint joinPoint, String keyExpression) {
    return spelParser.parse(joinPoint, keyExpression);
  }
}
