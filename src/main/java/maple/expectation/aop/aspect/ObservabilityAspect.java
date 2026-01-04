package maple.expectation.aop.aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
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
    public Object trackMetrics(ProceedingJoinPoint joinPoint, ObservedTransaction observedTransaction) {
        String metricName = observedTransaction.value();
        Timer.Sample sample = Timer.start(meterRegistry);

        // ✅ TaskContext 적용: Component="Observability", Operation="Track"
        return executor.executeWithRecovery(
                () -> this.executeAndRecordSuccess(joinPoint, metricName, sample),
                ex -> this.recordFailureAndThrow(metricName, joinPoint, sample, ex),
                TaskContext.of("Observability", "Track", metricName)
        );
    }

    private Object executeAndRecordSuccess(ProceedingJoinPoint joinPoint, String metricName, Timer.Sample sample) throws Throwable {
        Object result = joinPoint.proceed();
        sample.stop(Timer.builder(metricName)
                .tag("result", "success")
                .tag("class", joinPoint.getSignature().getDeclaringTypeName())
                .tag("method", joinPoint.getSignature().getName())
                .register(meterRegistry));
        return result;
    }

    private Object recordFailureAndThrow(String metricName, ProceedingJoinPoint joinPoint, Timer.Sample sample, Throwable e) {
        log.error("[Metric-Failure] ID: {}, Method: {}, Error: {}",
                metricName, joinPoint.getSignature().getName(), e.getMessage());

        sample.stop(Timer.builder(metricName)
                .tag("result", "failure")
                .tag("exception", e.getClass().getSimpleName())
                .register(meterRegistry));

        meterRegistry.counter(metricName + ".failure",
                "exception", e.getClass().getSimpleName()).increment();

        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        throw new RuntimeException("Observability tracking failed", e);
    }
}