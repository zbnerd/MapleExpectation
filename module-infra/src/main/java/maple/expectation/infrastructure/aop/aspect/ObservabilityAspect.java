package maple.expectation.infrastructure.aop.aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.ObservabilityException;
import maple.expectation.infrastructure.aop.annotation.ObservedTransaction;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ObservabilityAspect {

  private final MeterRegistry meterRegistry;
  private final LogicExecutor executor;

  @Around("@annotation(observedTransaction)")
  public Object trackMetrics(
      ProceedingJoinPoint joinPoint, ObservedTransaction observedTransaction) {
    String metricName = observedTransaction.value();
    Timer.Sample sample = Timer.start(meterRegistry);

    // ✅ TaskContext 적용: Component="Observability", Operation="Track"
    return executor.executeOrCatch(
        () -> this.executeAndRecordSuccess(joinPoint, metricName, sample),
        ex -> this.recordFailureAndThrow(metricName, joinPoint, sample, ex),
        TaskContext.of("Observability", "Track", metricName));
  }

  /**
   * 성공 시 메트릭 기록
   *
   * <p>Issue #138 FIX: 고카디널리티 태그 제거
   *
   * <ul>
   *   <li>제거: class, method 태그 (메트릭 폭발 방지)
   *   <li>유지: result 태그 (success/failure - 저카디널리티)
   * </ul>
   */
  private Object executeAndRecordSuccess(
      ProceedingJoinPoint joinPoint, String metricName, Timer.Sample sample) throws Throwable {
    Object result = joinPoint.proceed();

    // Issue #138: class, method 태그 제거 (고카디널리티 방지)
    sample.stop(Timer.builder(metricName).tag("result", "success").register(meterRegistry));

    return result;
  }

  /**
   * 실패 시 메트릭 기록 및 예외 재전파
   *
   * <p>Issue #138 FIX: 고카디널리티 태그 제거
   *
   * <ul>
   *   <li>제거: exception 태그 (메트릭 폭발 방지)
   *   <li>로그에는 여전히 상세 정보 기록 (디버깅용)
   * </ul>
   */
  private Object recordFailureAndThrow(
      String metricName, ProceedingJoinPoint joinPoint, Timer.Sample sample, Throwable e) {
    // 로그에는 상세 정보 유지 (디버깅용)
    log.error(
        "[Metric-Failure] ID: {}, Method: {}, Error: {}",
        metricName,
        joinPoint.getSignature().getName(),
        e.getMessage());

    // Issue #138: exception 태그 제거 (고카디널리티 방지)
    sample.stop(Timer.builder(metricName).tag("result", "failure").register(meterRegistry));

    // Issue #138: failure 카운터도 exception 태그 제거
    meterRegistry.counter(metricName + ".failure").increment();

    if (e instanceof RuntimeException) {
      throw (RuntimeException) e;
    }
    throw new ObservabilityException("Observability tracking failed", e);
  }
}
