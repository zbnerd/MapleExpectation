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

    private final LogicExecutor executor;
    private final ThreadLocal<Integer> depthHolder = ThreadLocal.withInitial(() -> 0);

    @Pointcut("execution(* maple.expectation.service..*.*(..)) " +
            "|| execution(* maple.expectation.external..*.*(..)) " +
            "|| execution(* maple.expectation.parser..*.*(..)) " +
            "|| execution(* maple.expectation.provider..*.*(..))")
    public void autoLog() {}

    @Pointcut("@annotation(maple.expectation.aop.annotation.TraceLog) || @within(maple.expectation.aop.annotation.TraceLog)")
    public void manualLog() {}

    @Pointcut("!execution(* maple.expectation.scheduler..*(..)) " +
            "&& !execution(* maple.expectation..LikeBufferStorage.*(..)) " +
            "&& !execution(* maple.expectation..LikeSyncService.*(..)) " +
            "&& !execution(* maple.expectation.monitoring..*(..))")
    public void excludeNoise() {}

    @Around("(autoLog() || manualLog()) && excludeNoise()")
    public Object doTrace(ProceedingJoinPoint joinPoint) {
        // 트레이스 비활성화 시 즉시 실행 (Legacy 오버로딩 활용)
        if (!isTraceEnabled) return executor.execute(joinPoint::proceed, "Trace:Bypass");

        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        TaskContext context = TaskContext.of("Trace", "Execute", className + "." + methodName);

        // 1. 실행 전 상태 준비 (인덴트 로깅 및 깊이 증가)
        TraceState state = prepareTrace(joinPoint, className, methodName);

        // 2. [패턴 1] executeWithFinally를 사용하여 try-catch-finally 키워드 박멸
        return executor.executeWithFinally(
                () -> this.proceedAndLog(joinPoint, state), // 실행 및 결과 로깅
                () -> this.cleanupTrace(state),            // 깊이 복구 및 스톱워치 종료
                context
        );
    }

    /**
     * 실행 전 준비: 깊이 증가 및 시작 로그 출력
     */
    private TraceState prepareTrace(ProceedingJoinPoint joinPoint, String className, String methodName) {
        int depth = depthHolder.get();
        String indent = "|  ".repeat(depth);
        String args = formatArgs(joinPoint.getArgs());

        log.info("{}--> [START] {}.{}(args: [{}])", indent, className, methodName, args);

        depthHolder.set(depth + 1);
        StopWatch sw = new StopWatch();
        sw.start();

        return new TraceState(depth, indent, className, methodName, sw);
    }

    /**
     * 실제 로직 실행 및 성공 결과 로깅
     */
    private Object proceedAndLog(ProceedingJoinPoint joinPoint, TraceState state) throws Throwable {
        // [패턴 5] 실행 중 예외 발생 시 전용 로깅 함수로 복구/기록
        return executor.executeOrCatch(
                joinPoint::proceed,
                ex -> this.handleTraceException(state, ex),
                TaskContext.of("Trace", "Proceed", state.methodName)
        );
    }

    /**
     * 종료 작업: 스톱워치 종료 및 깊이 원복 (Finally 블록 대체)
     */
    private void cleanupTrace(TraceState state) {
        state.sw.stop();
        if (state.depth > 0) depthHolder.set(state.depth);
        else depthHolder.remove();
    }

    /**
     * 예외 발생 시 로깅 처리 (Recovery 패턴)
     */
    private Object handleTraceException(TraceState state, Throwable e) {
        log.error("{}<X- [EXCEPTION] {}.{} throws {}",
                state.indent, state.className, state.methodName, e.getClass().getSimpleName());

        // 예외 기록 후 다시 던져서 비즈니스 흐름 유지
        if (e instanceof RuntimeException) throw (RuntimeException) e;
        throw new RuntimeException(e);
    }

    private String formatArgs(Object[] args) {
        return Arrays.stream(args)
                .map(arg -> {
                    if (arg == null) return "null";
                    if (arg instanceof byte[]) return "byte[" + ((byte[]) arg).length + "]";
                    return arg.toString();
                })
                .collect(Collectors.joining(", "));
    }

    /**
     * 트레이스 상태를 유지하기 위한 내부 Value Object
     */
    private record TraceState(int depth, String indent, String className, String methodName, StopWatch sw) {}
}