package maple.expectation.aop.aspect;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
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

    @Around("@annotation(observedTransaction)")
    public Object trackMetrics(ProceedingJoinPoint joinPoint, ObservedTransaction observedTransaction) throws Throwable {
        String metricName = observedTransaction.value();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            Object result = joinPoint.proceed(); // 실제 비즈니스 로직 실행

            // 성공 시 메트릭 기록
            sample.stop(Timer.builder(metricName)
                    .tag("result", "success")
                    .tag("class", joinPoint.getSignature().getDeclaringTypeName())
                    .tag("method", joinPoint.getSignature().getName())
                    .register(meterRegistry));

            return result;
        } catch (Throwable e) {
            log.error("[Metric-Failure] ID: {}, Method: {}, Error: {}",
                    metricName, joinPoint.getSignature().getName(), e.getMessage());
            // 실패 시 메트릭 기록 및 카운트 증가
            sample.stop(Timer.builder(metricName)
                    .tag("result", "failure")
                    .tag("exception", e.getClass().getSimpleName())
                    .register(meterRegistry));

            meterRegistry.counter(metricName + ".failure", 
                    "exception", e.getClass().getSimpleName()).increment();
            
            throw e; // 예외는 다시 던져서 기존 예외 처리가 작동하게 함
        }
    }
}