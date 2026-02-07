package maple.expectation.aop.aspect; // 패키지 변경됨

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
public class SimpleLogAspect {

  /**
   * ★ 중요: 포인트컷 경로가 변경되었습니다. maple.expectation.aop.SimpleLogTime ->
   * maple.expectation.aop.annotation.SimpleLogTime
   */
  @Around("@annotation(maple.expectation.aop.annotation.SimpleLogTime)")
  public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
    long start = System.currentTimeMillis();

    Object proceed = joinPoint.proceed();

    long duration = System.currentTimeMillis() - start;
    String methodName = joinPoint.getSignature().getName();

    log.info("⏱️ [Performance] 메서드: {} | 소요 시간: {}ms", methodName, duration);

    return proceed;
  }
}
