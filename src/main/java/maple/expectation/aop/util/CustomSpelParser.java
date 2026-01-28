package maple.expectation.aop.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SpEL 표현식 파싱 유틸리티 (LogicExecutor 평탄화 완료)
 *
 * <h3>#271 V5 Stateless Architecture 평가</h3>
 * <p>{@code expressionCache}는 ConcurrentHashMap이지만 다음 이유로 인스턴스별 유지 가능:</p>
 * <ul>
 *   <li>읽기 전용 캐시: SpEL Expression 파싱 결과 캐싱 (변경 없음)</li>
 *   <li>인스턴스별 독립: 각 인스턴스가 동일한 Expression을 파싱해도 결과 동일</li>
 *   <li>비즈니스 영향 없음: 캐시 없어도 기능 동작, 성능 최적화용</li>
 * </ul>
 *
 * <h4>5-Agent Council 합의 (P1-2)</h4>
 * <ul>
 *   <li>Blue (Architect): 읽기 전용 캐시로 Scale-out 안전</li>
 *   <li>Green (Performance): 인스턴스별 캐싱으로 JVM 내 최적화</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor // ✅ 생성자 주입 추가
public class CustomSpelParser {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final LogicExecutor executor; // ✅ 지능형 실행기 주입
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * SpEL 표현식을 파싱하여 String으로 반환
     */
    public String parse(ProceedingJoinPoint joinPoint, String expression) {
        return parseWithFallback(joinPoint, expression, joinPoint.getSignature().toShortString());
    }

    /**
     * ✅  parseWithFallback 평탄화
     * try-catch 대신 executeOrDefault를 사용하여 파싱 실패 시 안전하게 fallback 반환
     */
    public String parseWithFallback(ProceedingJoinPoint joinPoint, String expression, String fallback) {
        TaskContext context = TaskContext.of("SpelParser", "ParseString", expression);

        return executor.executeOrDefault(
                () -> {
                    StandardEvaluationContext evalContext = createEvaluationContext(joinPoint);

                    // 1. 캐시에서 꺼내거나 없으면 파싱해서 저장 (변수명 수정: expression)
                    Expression expr = expressionCache.computeIfAbsent(expression, parser::parseExpression);

                    // 2. 캐시된 expr 객체로 바로 평가 (성능 최적화)
                    return expr.getValue(evalContext, String.class);
                },
                fallback,
                context
        );
    }

    /**
     * ✅  제네릭 parse 평탄화
     */
    public <T> T parse(ProceedingJoinPoint joinPoint, String expression, Class<T> resultType, T fallback) {
        TaskContext context = TaskContext.of("SpelParser", "ParseGeneric", expression);

        return executor.executeOrDefault(
                () -> {
                    StandardEvaluationContext evalContext = createEvaluationContext(joinPoint);
                    return parser.parseExpression(expression).getValue(evalContext, resultType);
                },
                fallback,
                context
        );
    }

    /**
     * ProceedingJoinPoint에서 메서드 파라미터를 추출하여 EvaluationContext 생성
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