package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import maple.expectation.aop.annotation.Locked;
import maple.expectation.global.lock.LockStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

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

        // 락 전략을 사용하여 비즈니스 로직(joinPoint.proceed()) 실행
        return lockStrategy.executeWithLock(key, () -> {
            try {
                return joinPoint.proceed();
            } catch (Throwable e) {
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException(e);
            }
        });
    }

    private String getDynamicKey(ProceedingJoinPoint joinPoint, String keyExpression) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        StandardEvaluationContext context = new StandardEvaluationContext();
        
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        
        for (int i = 0; i < parameterNames.length; i++) {
            context.setVariable(parameterNames[i], args[i]);
        }
        
        return parser.parseExpression(keyExpression).getValue(context, String.class);
    }
}