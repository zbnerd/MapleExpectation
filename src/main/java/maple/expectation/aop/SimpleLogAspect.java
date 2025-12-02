package maple.expectation.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
public class SimpleLogAspect {

    @Around("@annotation(maple.expectation.aop.SimpleLogTime)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        Object proceed = joinPoint.proceed(); // 메서드 실행

        long duration = System.currentTimeMillis() - start;
        String methodName = joinPoint.getSignature().getName();

        log.info("⏱️ [Performance] 메서드: {} | 소요 시간: {}ms", methodName, duration);

        return proceed;
    }
}