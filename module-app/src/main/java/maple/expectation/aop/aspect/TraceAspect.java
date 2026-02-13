package maple.expectation.aop.aspect;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

/**
 * 트레이스 어스펙트 (#271 V5 Stateless Architecture)
 *
 * <h3>V5 Stateless 전환</h3>
 *
 * <p>ThreadLocal에서 MDC(Mapped Diagnostic Context)로 마이그레이션:
 *
 * <ul>
 *   <li>call depth를 MDC "traceDepth" 키로 관리
 *   <li>로그에서 depth 확인 가능 → Observability 향상
 *   <li>depth==0이면 MDC.remove()로 완전 정리 (스레드풀 누수 방지)
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): MDC 전환으로 로그 추적성 확보
 *   <li>Red (SRE): remove() 보장으로 스레드풀 누수 방지
 * </ul>
 *
 * <p>순환 참조 방지를 위해 LogicExecutor 의존성을 제거하고 try-catch-finally 패턴 적용
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

  /** V5: MDC 키 (로그에서 확인 가능) */
  private static final String MDC_DEPTH_KEY = "traceDepth";

  // ❌ LogicExecutor 제거 (순환 참조 원인)
  // private final LogicExecutor executor;

  // V5: ThreadLocal → MDC 마이그레이션
  // private final ThreadLocal<Integer> depthHolder = ThreadLocal.withInitial(() -> 0);

  @Pointcut(
      "execution(* maple.expectation.service..*.*(..)) "
          + "|| execution(* maple.expectation.external..*.*(..)) "
          + "|| execution(* maple.expectation.parser..*.*(..)) "
          + "|| execution(* maple.expectation.provider..*.*(..))"
          + "|| execution(* maple.expectation.repository..*.*(..))"
          + "|| execution(* maple.expectation.error..*.*(..))"
          + "|| execution(* maple.expectation.util..*.*(..))"
          + "|| execution(* maple.expectation.controller..*.*(..))")
  public void autoLog() {}

  @Pointcut(
      "@annotation(maple.expectation.aop.annotation.TraceLog) || @within(maple.expectation.aop.annotation.TraceLog)")
  public void manualLog() {}

  @Pointcut(
      "!execution(* maple.expectation.scheduler..*(..)) "
          + "&& !execution(* maple.expectation..LikeBufferStorage.*(..)) "
          + "&& !execution(* maple.expectation..LikeSyncService.*(..)) "
          + "&& !execution(* maple.expectation.monitoring..*(..)) "
          + "&& !@annotation(org.springframework.scheduling.annotation.Scheduled) "
          +
          // LogicExecutor 내부 로직은 로깅 대상에서 제외 (재귀 호출 방지)
          "&& !within(maple.expectation.infrastructure.executor..*)"
          + "&& !within(*..*LockStrategy*) "
          + "&& !execution(* *..*LockStrategy*.executeWithLock(..))"
          + "&& !execution(* maple.expectation..RedisBufferRepository.getTotalPendingCount(..))"
          +
          // LikeRelation 관련 노이즈 제거 (스케줄러에서 주기적 호출)
          "&& !within(maple.expectation.service.v2.LikeRelationSyncService) "
          + "&& !within(maple.expectation.service.v2.cache.LikeRelationBuffer) "
          +
          // API Key 노출 방지 (String 파라미터로 전달되어 마스킹 불가)
          //                    "&& !within(maple.expectation.external.impl.RealNexonAuthClient) " +
          "&& !execution(* *.toString())"
          + "&& !execution(* *.hashCode())"
          + "&& !execution(* *.equals(..))")
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

  private TraceState prepareTrace(
      ProceedingJoinPoint joinPoint, String className, String methodName) {
    int depth = getMdcDepth();
    String indent = "|  ".repeat(depth);
    String args = formatArgs(joinPoint.getArgs());

    log.info("{}--> [START] {}.{}(args: [{}])", indent, className, methodName, args);

    setMdcDepth(depth + 1);
    StopWatch sw = new StopWatch();
    sw.start();

    return new TraceState(depth, indent, className, methodName, sw);
  }

  private void cleanupTrace(TraceState state) {
    state.sw.stop();
    long tookMs = state.sw.getTotalTimeMillis();

    if (state.isSuccess) {
      log.info(
          "{}<-- [END] {}.{} ({} ms)", state.indent, state.className, state.methodName, tookMs);
    }

    // V5: depth 복원 (state.depth는 "이전" depth)
    if (state.depth > 0) {
      setMdcDepth(state.depth);
    } else {
      MDC.remove(MDC_DEPTH_KEY); // V5: depth==0이면 MDC 정리 (스레드풀 누수 방지)
    }
  }

  private void handleTraceException(TraceState state, Throwable e) {
    log.error(
        "{}<X- [EXCEPTION] {}.{} throws {}",
        state.indent,
        state.className,
        state.methodName,
        e.getClass().getSimpleName());
  }

  // ==================== V5: MDC 기반 Depth 관리 ====================

  /**
   * MDC에서 현재 depth 조회
   *
   * @return 현재 depth (기본값: 0)
   */
  private int getMdcDepth() {
    String depthStr = MDC.get(MDC_DEPTH_KEY);
    if (depthStr == null) {
      return 0;
    }
    try {
      return Integer.parseInt(depthStr);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * MDC에 depth 설정
   *
   * @param depth 설정할 depth 값
   */
  private void setMdcDepth(int depth) {
    MDC.put(MDC_DEPTH_KEY, String.valueOf(depth));
  }

  private String formatArgs(Object[] args) {
    if (args == null || args.length == 0) return "";

    return Arrays.stream(args)
        .map(
            arg -> {
              if (arg == null) return "null";
              if (arg instanceof byte[]) return "byte[" + ((byte[]) arg).length + "]";
              if (arg instanceof Collection<?>)
                return "Collection(size=" + ((Collection<?>) arg).size() + ")";
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
