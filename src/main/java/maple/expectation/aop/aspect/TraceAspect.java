package maple.expectation.aop.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Value; // import 추가
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
@Aspect
@Component
public class TraceAspect {

    // ✅ yaml에서 설정값 가져오기 (기본값: false)
    @Value("${app.aop.trace.enabled:false}")
    private boolean isTraceEnabled;

    private final ThreadLocal<Integer> depthHolder = ThreadLocal.withInitial(() -> 0);

    @Pointcut("@annotation(maple.expectation.aop.annotation.TraceLog) || @within(maple.expectation.aop.annotation.TraceLog)")
    public void traceTarget() {}

    @Around("traceTarget()")
    public Object doTrace(ProceedingJoinPoint joinPoint) throws Throwable {
        // ✅ [Switch] 설정이 꺼져있으면 AOP 로직을 건너뛰고 바로 실행
        // (운영 환경 오버헤드 최소화)
        if (!isTraceEnabled) {
            return joinPoint.proceed();
        }

        // --- 이하 기존 로직 동일 ---
        int depth = depthHolder.get();
        String indent = getIndent(depth);
        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String methodName = joinPoint.getSignature().getName();

        String args = Arrays.stream(joinPoint.getArgs())
                .map(arg -> {
                    if (arg == null) return "null";
                    if (arg instanceof byte[]) return "byte[" + ((byte[]) arg).length + "]";
                    return arg.toString();
                })
                .collect(Collectors.joining(", "));

        log.info("{}--> [START] {}.{}(args: [{}])", indent, className, methodName, args);

        depthHolder.set(depth + 1);
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        Object result = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            log.error("{}<X- [EXCEPTION] {}.{} throws {}", indent, className, methodName, e.getClass().getSimpleName());
            throw e;
        } finally {
            stopWatch.stop();
            depthHolder.set(depth);

            String resultString = "void";
            if (result != null) {
                resultString = result.toString();
                if (result instanceof java.util.List) {
                    resultString = "List(size=" + ((java.util.List<?>) result).size() + ")";
                } else if (resultString.length() > 100) {
                    resultString = resultString.substring(0, 100) + "...";
                }
            }

            log.info("{}<-- [END] {}.{} (Return: {}) [{}ms]",
                    indent, className, methodName, resultString, stopWatch.getTotalTimeMillis());
        }
    }

    private String getIndent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("|  ");
        }
        return sb.toString();
    }
}