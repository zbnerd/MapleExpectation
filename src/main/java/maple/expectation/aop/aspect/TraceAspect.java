package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.stream.Collectors;

/**
 * 트레이스 어스펙트
 * - 순환 참조 방지를 위해 LogicExecutor 의존성을 제거하고 try-catch-finally 패턴 적용
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@Order(-1)
public class TraceAspect {

    @Value("${app.aop.trace.enabled:false}")
    private boolean isTraceEnabled;

    private static final int MAX_ARG_LENGTH = 100;

    // ❌ LogicExecutor 제거 (순환 참조 원인)
    // private final LogicExecutor executor;

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
                    // LogicExecutor 내부 로직은 로깅 대상에서 제외 (재귀 호출 방지)
                    "&& !within(maple.expectation.global.executor..*)" +
                    "&& !within(maple.expectation.global.filter..*) " +
                    "&& !within(*..*LockStrategy*) " +
                    "&& !execution(* *..*LockStrategy*.executeWithLock(..))" +
                    "&& !execution(* maple.expectation..RedisBufferRepository.getTotalPendingCount(..))" +
                    // LikeRelation 관련 노이즈 제거 (스케줄러에서 주기적 호출)
                    "&& !within(maple.expectation.service.v2.LikeRelationSyncService) " +
                    "&& !within(maple.expectation.service.v2.cache.LikeRelationBuffer) " +
                    // API Key 노출 방지 (String 파라미터로 전달되어 마스킹 불가)
//                    "&& !within(maple.expectation.external.impl.RealNexonAuthClient) " +
                    "&& !execution(* *.toString())" +
                    "&& !execution(* *.hashCode())" +
                    "&& !execution(* *.equals(..))"
    )
    public void excludeNoise() {}

    @Around("(autoLog() || manualLog()) && excludeNoise()")
    public Object doTrace(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!isTraceEnabled || !log.isInfoEnabled()) {
            return joinPoint.proceed();
        }

        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String methodName = joinPoint.getSignature().getName();

        TraceState state = prepareTrace(joinPoint, className, methodName);

        try {
            // [핵심] LogicExecutor 대신 직접 proceed 호출
            Object result = joinPoint.proceed();
            state.onSuccess(); // 성공 표시
            return result;
        } catch (Throwable e) {
            handleTraceException(state, e);
            throw e; // 예외를 숨기지 않고 상위로 전파 (투명성 보장)
        } finally {
            cleanupTrace(state);
        }
    }

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

    private void cleanupTrace(TraceState state) {
        state.sw.stop();
        long tookMs = state.sw.getTotalTimeMillis();

        if (state.isSuccess) {
            log.info("{}<-- [END] {}.{} ({} ms)", state.indent, state.className, state.methodName, tookMs);
        }

        if (state.depth > 0) depthHolder.set(state.depth);
        else depthHolder.remove();
    }

    private void handleTraceException(TraceState state, Throwable e) {
        log.error("{}<X- [EXCEPTION] {}.{} throws {}",
                state.indent, state.className, state.methodName, e.getClass().getSimpleName());
    }

    private String formatArgs(Object[] args) {
        if (args == null || args.length == 0) return "";

        return Arrays.stream(args)
                .map(arg -> {
                    if (arg == null) return "null";
                    if (arg instanceof byte[]) return "byte[" + ((byte[]) arg).length + "]";
                    if (arg instanceof Collection<?>) return "Collection(size=" + ((Collection<?>) arg).size() + ")";
                    if (arg instanceof Map<?, ?>) return "Map(size=" + ((Map<?, ?>) arg).size() + ")";

                    String str = arg.toString();
                    if (str.length() > MAX_ARG_LENGTH) {
                        return str.substring(0, MAX_ARG_LENGTH) + "...(len:" + str.length() + ")";
                    }
                    return str;
                })
                .collect(Collectors.joining(", "));
    }

    // 상태 관리용 클래스 (Record 아님 - 가변 상태 isSuccess 변경 필요)
    private static class TraceState {
        final int depth;
        final String indent;
        final String className;
        final String methodName;
        final StopWatch sw;
        boolean isSuccess = false; // 기본 실패 가정

        TraceState(int depth, String indent, String className, String methodName, StopWatch sw) {
            this.depth = depth;
            this.indent = indent;
            this.className = className;
            this.methodName = methodName;
            this.sw = sw;
        }

        void onSuccess() {
            this.isSuccess = true;
        }
    }
}