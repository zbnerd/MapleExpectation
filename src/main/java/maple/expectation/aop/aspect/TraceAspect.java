package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 트레이스 어스펙트 (LogicExecutor 기반 평탄화 완료)
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@Order(-1)
public class TraceAspect {

    @Value("${app.aop.trace.enabled:false}")
    private boolean isTraceEnabled;

    private static final int MAX_ARG_LENGTH = 100; // 인자 최대 길이 (넘어가면 잘림)

    private final LogicExecutor executor;
    private final ThreadLocal<Integer> depthHolder = ThreadLocal.withInitial(() -> 0);

    @Pointcut("execution(* maple.expectation.service..*.*(..)) " +
            "|| execution(* maple.expectation.external..*.*(..)) " +
            "|| execution(* maple.expectation.parser..*.*(..)) " +
            "|| execution(* maple.expectation.provider..*.*(..))" +
            "|| execution(* maple.expectation.repository..*.*(..))" +
            "|| execution(* maple.expectation.global..*.*(..))" +
            "|| execution(* maple.expectation.util..*.*(..))" +
            "|| execution(* maple.expectation.controller..*.*(..))")
    public void autoLog() {}

    @Pointcut("@annotation(maple.expectation.aop.annotation.TraceLog) || @within(maple.expectation.aop.annotation.TraceLog)")
    public void manualLog() {}

    @Pointcut(
            "!execution(* maple.expectation.scheduler..*(..)) " +
                    "&& !execution(* maple.expectation..LikeBufferStorage.*(..)) " +
                    "&& !execution(* maple.expectation..LikeSyncService.*(..)) " +
                    "&& !execution(* maple.expectation.monitoring..*(..)) " +
                    "&& !@annotation(org.springframework.scheduling.annotation.Scheduled) " +
                    "&& !within(maple.expectation.global.executor..*)" +
                    "&& !within(maple.expectation.global.filter..*) " +
                    "&& !within(*..*LockStrategy*) " +
                    "&& !execution(* *..*LockStrategy*.executeWithLock(..))" +
                    "&& !execution(* maple.expectation..RedisBufferRepository.getTotalPendingCount(..))" +
                    "&& !execution(* *.toString())" +
                    "&& !execution(* *.hashCode())" +
                    "&& !execution(* *.equals(..))"
    )
    public void excludeNoise() {}

    @Around("(autoLog() || manualLog()) && excludeNoise()")
    public Object doTrace(ProceedingJoinPoint joinPoint) {
        if (!isTraceEnabled || !log.isInfoEnabled()) return executor.execute(joinPoint::proceed, "Trace:Bypass");

        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        TaskContext context = TaskContext.of("Trace", "Execute", className + "." + methodName);

        TraceState state = prepareTrace(joinPoint, className, methodName);

        return executor.executeWithFinally(
                () -> this.proceedAndLog(joinPoint, state),
                () -> this.cleanupTrace(state),
                context
        );
    }

    private TraceState prepareTrace(ProceedingJoinPoint joinPoint, String className, String methodName) {
        int depth = depthHolder.get();
        String indent = "|  ".repeat(depth);
        String args = formatArgs(joinPoint.getArgs());

        log.info("{}--> [START] {}.{}(args: [{}])", indent, className, methodName, args);

        depthHolder.set(depth + 1);
        StopWatch sw = new StopWatch();
        sw.start();

        return new TraceState(depth, indent, className, methodName, sw, new AtomicBoolean(false));
    }

    private Object proceedAndLog(ProceedingJoinPoint joinPoint, TraceState state) throws Throwable {
        return executor.executeOrCatch(
                () -> {
                    Object result = joinPoint.proceed();
                    state.success.set(true);
                    return result;
                },
                ex -> this.handleTraceException(state, ex),
                TaskContext.of("Trace", "Proceed", state.methodName)
        );
    }

    private void cleanupTrace(TraceState state) {
        state.sw.stop();
        long tookMs = state.sw.getTotalTimeMillis();

        if (state.success.get()) {
            log.info("{}<-- [END] {}.{} ({} ms)", state.indent, state.className, state.methodName, tookMs);
        }

        if (state.depth > 0) depthHolder.set(state.depth);
        else depthHolder.remove();
    }

    private Object handleTraceException(TraceState state, Throwable e) {
        log.error("{}<X- [EXCEPTION] {}.{} throws {}",
                state.indent, state.className, state.methodName, e.getClass().getSimpleName());

        if (e instanceof RuntimeException) throw (RuntimeException) e;
        throw new RuntimeException(e);
    }

    private String formatArgs(Object[] args) {
        if (args == null || args.length == 0) return ""; // 방어 로직

        return Arrays.stream(args)
                .map(arg -> {
                    if (arg == null) return "null";

                    if (arg instanceof byte[]) return "byte[" + ((byte[]) arg).length + "]";

                    // 2. [추가] 컬렉션/맵은 내용 대신 '크기'만 출력 (메모리 폭발 방지 핵심)
                    if (arg instanceof Collection<?>) return "Collection(size=" + ((Collection<?>) arg).size() + ")";
                    if (arg instanceof Map<?, ?>) return "Map(size=" + ((Map<?, ?>) arg).size() + ")";

                    // 3. 그 외 객체는 toString() 호출 후 자르기
                    String str = arg.toString();
                    if (str.length() > MAX_ARG_LENGTH) {
                        return str.substring(0, MAX_ARG_LENGTH) + "...(len:" + str.length() + ")";
                    }
                    return str;
                })
                .collect(Collectors.joining(", "));
    }

    private record TraceState(
            int depth,
            String indent,
            String className,
            String methodName,
            StopWatch sw,
            AtomicBoolean success
    ) {}
}