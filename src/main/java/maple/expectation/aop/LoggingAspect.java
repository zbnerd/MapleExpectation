package maple.expectation.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
public class LoggingAspect {

    /**
     * @Around: @LogExecutionTime 어노테이션이 붙은 모든 메서드의 실행을 감싸서 실행합니다.
     * ProceedingJoinPoint를 통해 원본 메서드를 실행하고 그 전후로 로직을 삽입합니다.
     */

    @Around("@annotation(maple.expectation.aop.LogExecutionTime)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis(); // 1. 측정시작시간

        // 실제 biz 로직실행 원본메서드 호출
        Object proceed = joinPoint.proceed();

        long end = System.currentTimeMillis(); // 3. 측정종료시간

        String methodName = joinPoint.getSignature().toShortString();
        log.info("📊 [AOP TIME CHECK] {} 실행 완료. 소요 시간: {}ms", methodName, end-start);

        return proceed; // 결과 호출지점으로 반환
    }

}
