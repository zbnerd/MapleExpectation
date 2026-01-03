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

        // 🎯 SSOT: 어노테이션에서 락 타이밍 정책 읽기
        long waitTime = locked.waitTime();
        long leaseTime = locked.leaseTime();
        long waitSeconds = locked.timeUnit().toSeconds(waitTime);
        long leaseSeconds = locked.timeUnit().toSeconds(leaseTime);

        try {
            // 1️⃣ [Distributed Lock] 어노테이션 설정값으로 락 획득
            // 1등이 넥슨 API에서 OCID를 가져와 DB에 저장할 시간을 충분히 벌어줍니다.
            return lockStrategy.executeWithLock(key, waitSeconds, leaseSeconds, () -> {
                log.debug("🔑 [Locked Aspect] 락 획득 성공: {}", key);
                return joinPoint.proceed();
            });
        } catch (DistributedLockException e) {
            // 2️⃣ [Fallback] 대기 시간 내에 락을 못 잡은 경우 (나머지 99명)
            log.warn("⏭️ [Locked Timeout] {} - 락 획득 실패. 직접 조회를 시도합니다.", key);

            // 락은 못 잡았지만, 그 사이 1등이 DB에 캐릭터를 생성했을 확률이 매우 높습니다.
            // 에러를 던지는 대신 조회를 시도하여 유저에게 정상 응답을 줍니다.
            return joinPoint.proceed();
        } catch (Throwable e) {
            // 비즈니스 예외 등은 그대로 전파
            throw e;
        }
    }

    private String getDynamicKey(ProceedingJoinPoint joinPoint, String keyExpression) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        StandardEvaluationContext context = new StandardEvaluationContext();

        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }

        try {
            return parser.parseExpression(keyExpression).getValue(context, String.class);
        } catch (Exception e) {
            return joinPoint.getSignature().toShortString();
        }
    }
}