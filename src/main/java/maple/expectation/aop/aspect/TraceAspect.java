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
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Aspect
@Component
public class TraceAspect {

    @Value("${app.aop.trace.enabled:false}")
    private boolean isTraceEnabled;

    private final ThreadLocal<Integer> depthHolder = ThreadLocal.withInitial(() -> 0);

    // 🎯 [수정] 감시망 확대: 서비스, 외부 API, 파서, 계산기 등을 모두 포함
    @Pointcut("execution(* maple.expectation.service..*.*(..)) " +
            "|| execution(* maple.expectation.external..*.*(..)) " +
            "|| execution(* maple.expectation.parser..*.*(..)) " +
            "|| execution(* maple.expectation.provider..*.*(..))")
    public void autoLog() {}

    // 🎯 수동으로 @TraceLog 붙인 곳도 포함
    @Pointcut("@annotation(maple.expectation.aop.annotation.TraceLog) || @within(maple.expectation.aop.annotation.TraceLog)")
    public void manualLog() {}

    // 🎯 스케줄러 소음 제거
    @Pointcut("!execution(* maple.expectation.scheduler..*(..)) " +
            "&& !execution(* maple.expectation..LikeBufferStorage.*(..)) " +
            "&& !execution(* maple.expectation..LikeSyncService.*(..)) " +
            "&& !execution(* maple.expectation.mornitering..*(..))")
    public void excludeNoise() {}

    @Pointcut("!execution(* *.syncLikesToDatabase(..))")
    public void excludeSpecificMethod() {}

    @Around("(autoLog() || manualLog()) && excludeNoise() && excludeSpecificMethod()")
    public Object doTrace(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!isTraceEnabled) {
            return joinPoint.proceed();
        }

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

        log.debug("{}--> [START] {}.{}(args: [{}])", indent, className, methodName, args);

        depthHolder.set(depth + 1);
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        Object result = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            log.debug("{}<X- [EXCEPTION] {}.{} throws {}", indent, className, methodName, e.getClass().getSimpleName());
            throw e;
        } finally {
            stopWatch.stop();
            // 리소스 해제 로직
            Optional.of(depth)
                    .filter(d -> d > 0)
                    .ifPresentOrElse(depthHolder::set, depthHolder::remove);

            String resultString = (result != null) ? result.toString() : "void";
            if (result instanceof java.util.List) resultString = "List(size=" + ((java.util.List<?>) result).size() + ")";
            else if (resultString.length() > 100) resultString = resultString.substring(0, 100) + "...";

            log.debug("{}<-- [END] {}.{} (Return: {}) [{}ms]",
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