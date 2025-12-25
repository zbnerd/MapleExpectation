package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.Locked;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.global.lock.LockStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Order(0)
@Component
@RequiredArgsConstructor
public class LockAspect {

    private final LockStrategy lockStrategy;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(locked)")
    public Object applyLock(ProceedingJoinPoint joinPoint, Locked locked) throws Throwable {
        String key = getDynamicKey(joinPoint, locked.key());

        // 💡 락 전략 실행 시 내부 로직을 별도 메서드로 래핑하여 괄호 지옥을 탈출합니다.
        return lockStrategy.executeWithLock(key, () -> proceedWithExceptionHandling(joinPoint, key));
    }

    private Object proceedWithExceptionHandling(ProceedingJoinPoint joinPoint, String key) {
        try {
            return joinPoint.proceed();
        } catch (RuntimeException e) {
            // 💡 비즈니스 예외는 그대로 통과
            throw e;
        } catch (Throwable e) {
            // 💡 그 외의 모든 기술적 체크 예외는 락 예외로 변환하여 던짐 (S002 매핑)
            throw new DistributedLockException(key, e);
        }
    }

    private String getDynamicKey(ProceedingJoinPoint joinPoint, String keyExpression) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        StandardEvaluationContext context = new StandardEvaluationContext();

        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameterNames.length; i++) {
            context.setVariable(parameterNames[i], args[i]);
        }

        try {
            return parser.parseExpression(keyExpression).getValue(context, String.class);
        } catch (Exception e) {
            // Spel 파싱 실패 시 메서드 이름이라도 반환하여 최소한의 방어
            return joinPoint.getSignature().toShortString();
        }
    }
}