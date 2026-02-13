package maple.expectation.infrastructure.executor.policy;

import static maple.expectation.infrastructure.executor.policy.TaskLogTags.*;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.core.annotation.Order;

/**
 * 작업 실행 단계별 로깅을 수행하는 정책 (Stateless)
 *
 * <p>- before: [Task:START] {taskName} -> DEBUG - onSuccess: (normal) [Task:SUCCESS] {taskName},
 * elapsed=... -> DEBUG (slow) [Task:SLOW] {taskName}, elapsed=..., threshold=...ms -> INFO (비활성 시
 * DEBUG 폴백) - onFailure: [Task:FAILURE] {taskName}, elapsed=..., errorType=... (stacktrace) ->
 * ERROR - after: [Task:AFTER] {taskName}, outcome=..., elapsed=... -> DEBUG
 */
@Slf4j
@Order(PolicyOrder.LOGGING)
public class LoggingPolicy implements ExecutionPolicy {

  private static final long MAX_SLOW_MS = 60_000L;

  private final boolean slowEnabled;
  private final long slowThresholdMs;
  private final long slowThresholdNanos;

  /**
   * @param slowMs slow 판정 임계치(ms). 0 이하: SLOW 기능 비활성 (모든 성공 로그가 일반 SUCCESS로 처리) 1 이상: 이 값 이상이면 SLOW
   *     로그로 승격(INFO, 비활성 시 DEBUG 폴백)
   */
  public LoggingPolicy(long slowMs) {
    // 방어: 음수는 0, 과도한 값은 상한으로 클램프
    long clamped = Math.max(0L, Math.min(slowMs, MAX_SLOW_MS));

    this.slowThresholdMs = clamped;
    this.slowEnabled = clamped > 0;
    this.slowThresholdNanos =
        slowEnabled ? TimeUnit.MILLISECONDS.toNanos(clamped) : Long.MAX_VALUE; // 비활성: 절대 도달 불가
  }

  @Override
  public FailureMode failureMode() {
    // 로깅 실패가 실행 결과를 바꾸지 않도록 SWALLOW 권장
    return FailureMode.SWALLOW;
  }

  @Override
  public void before(TaskContext context) {
    if (!log.isDebugEnabled()) return;
    log.debug("{} {}", TAG_START, TaskLogSupport.safeTaskName(context));
  }

  @Override
  public <T> void onSuccess(T ignored, long elapsedNanos, TaskContext context) {
    boolean slow = slowEnabled && elapsedNanos >= slowThresholdNanos;

    if (slow) {
      String taskName = TaskLogSupport.safeTaskName(context);
      String elapsed = formatDuration(elapsedNanos);

      if (log.isInfoEnabled()) {
        log.info("{} {}, elapsed={}, threshold={}ms", TAG_SLOW, taskName, elapsed, slowThresholdMs);
        return;
      }
      // INFO가 꺼져있어도 SLOW 태그는 유지 (운영 관측 일관성)
      if (!log.isDebugEnabled()) return;
      log.debug("{} {}, elapsed={}, threshold={}ms", TAG_SLOW, taskName, elapsed, slowThresholdMs);
      return;
    }

    if (!log.isDebugEnabled()) return;

    String taskName = TaskLogSupport.safeTaskName(context);
    String elapsed = formatDuration(elapsedNanos);
    log.debug("{} {}, elapsed={}", TAG_SUCCESS, taskName, elapsed);
  }

  @Override
  public void onFailure(Throwable error, long elapsedNanos, TaskContext context) {
    if (!log.isErrorEnabled()) return;

    String taskName = TaskLogSupport.safeTaskName(context);
    String elapsed = formatDuration(elapsedNanos);
    String errorType = (error != null) ? error.getClass().getSimpleName() : "UnknownError";

    log.error("{} {}, elapsed={}, errorType={}", TAG_FAILURE, taskName, elapsed, errorType, error);
  }

  @Override
  public void after(ExecutionOutcome outcome, long elapsedNanos, TaskContext context) {
    if (!log.isDebugEnabled()) return;

    String taskName = TaskLogSupport.safeTaskName(context);
    String elapsed = formatDuration(elapsedNanos);

    log.debug("{} {}, outcome={}, elapsed={}", TAG_AFTER, taskName, outcome, elapsed);
  }

  private static String formatDuration(long elapsedNanos) {
    double millis = elapsedNanos / 1_000_000d;
    return String.format(Locale.ROOT, "%.3fms", millis);
  }
}
