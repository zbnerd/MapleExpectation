package maple.expectation.aop.util;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * SpEL 표현식 파싱 유틸리티
 *
 * <p>Aspect에서 SpEL 파싱 로직을 캡슐화하여 재사용성을 높입니다.
 *
 * <h3>Before (Aspect 내부에 SpEL 파싱 로직)</h3>
 * <pre>{@code
 * private final ExpressionParser parser = new SpelExpressionParser();
 *
 * private String getDynamicKey(ProceedingJoinPoint joinPoint, String keyExpression) {
 *     MethodSignature signature = (MethodSignature) joinPoint.getSignature();
 *     StandardEvaluationContext context = new StandardEvaluationContext();
 *
 *     String[] parameterNames = signature.getParameterNames();
 *     Object[] args = joinPoint.getArgs();
 *
 *     if (parameterNames != null) {
 *         for (int i = 0; i < parameterNames.length; i++) {
 *             context.setVariable(parameterNames[i], args[i]);
 *         }
 *     }
 *
 *     try {
 *         return parser.parseExpression(keyExpression).getValue(context, String.class);
 *     } catch (Exception e) {
 *         return joinPoint.getSignature().toShortString();
 *     }
 * }
 * }</pre>
 *
 * <h3>After (CustomSpelParser 사용)</h3>
 * <pre>{@code
 * @RequiredArgsConstructor
 * public class LockAspect {
 *     private final CustomSpelParser spelParser;
 *
 *     private String getDynamicKey(ProceedingJoinPoint joinPoint, String keyExpression) {
 *         return spelParser.parse(joinPoint, keyExpression);
 *     }
 * }
 * }</pre>
 *
 * <h3>개선 효과</h3>
 * <ul>
 *   <li>Aspect 코드에서 ExpressionParser 객체가 보이지 않음</li>
 *   <li>다른 Aspect에서도 재사용 가능 (TraceAspect, CacheAspect 등)</li>
 *   <li>SpEL 파싱 로직 테스트 용이</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
@Component
public class CustomSpelParser {

    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * SpEL 표현식을 파싱하여 String으로 반환
     *
     * @param joinPoint AOP ProceedingJoinPoint (메서드 파라미터 추출용)
     * @param expression SpEL 표현식 (예: "#characterName", "#ocid")
     * @return 파싱된 문자열 (실패 시 메서드 시그니처)
     */
    public String parse(ProceedingJoinPoint joinPoint, String expression) {
        return parseWithFallback(joinPoint, expression, joinPoint.getSignature().toShortString());
    }

    /**
     * SpEL 표현식을 파싱하여 String으로 반환 (fallback 값 지정 가능)
     *
     * @param joinPoint AOP ProceedingJoinPoint
     * @param expression SpEL 표현식
     * @param fallback 파싱 실패 시 반환할 기본값
     * @return 파싱된 문자열 (실패 시 fallback 값)
     */
    public String parseWithFallback(ProceedingJoinPoint joinPoint, String expression, String fallback) {
        try {
            StandardEvaluationContext context = createEvaluationContext(joinPoint);
            return parser.parseExpression(expression).getValue(context, String.class);
        } catch (Exception e) {
            log.trace("SpEL 파싱 실패 (expression: {}, fallback: {}): {}",
                expression, fallback, e.getMessage());
            return fallback;
        }
    }

    /**
     * SpEL 표현식을 파싱하여 지정된 타입으로 반환
     *
     * @param joinPoint AOP ProceedingJoinPoint
     * @param expression SpEL 표현식
     * @param resultType 결과 타입
     * @param fallback 파싱 실패 시 반환할 기본값
     * @param <T> 결과 타입
     * @return 파싱된 값 (실패 시 fallback 값)
     */
    public <T> T parse(ProceedingJoinPoint joinPoint, String expression, Class<T> resultType, T fallback) {
        try {
            StandardEvaluationContext context = createEvaluationContext(joinPoint);
            return parser.parseExpression(expression).getValue(context, resultType);
        } catch (Exception e) {
            log.trace("SpEL 파싱 실패 (expression: {}, type: {}, fallback: {}): {}",
                expression, resultType.getSimpleName(), fallback, e.getMessage());
            return fallback;
        }
    }

    /**
     * ProceedingJoinPoint에서 메서드 파라미터를 추출하여 EvaluationContext 생성
     *
     * <p>메서드 파라미터 이름과 값을 SpEL 변수로 등록합니다.
     *
     * @param joinPoint AOP ProceedingJoinPoint
     * @return SpEL EvaluationContext
     */
    private StandardEvaluationContext createEvaluationContext(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        StandardEvaluationContext context = new StandardEvaluationContext();

        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }

        return context;
    }
}
