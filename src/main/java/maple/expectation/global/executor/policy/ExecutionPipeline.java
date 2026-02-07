package maple.expectation.global.executor.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.global.util.InterruptUtils;

/**
 * ExecutionPolicy를 순차 실행하는 파이프라인.
 *
 * <h3>핵심 보장 (PRD v4 준수)</h3>
 *
 * <ul>
 *   <li><b>elapsedNanos는 task.get() 구간만 측정</b> (정책 시간 제외)
 *   <li><b>BEFORE는 등록 순서, AFTER는 역순(LIFO)</b>
 *   <li><b>before() 성공한 정책만 after() 호출</b> (entered pairing)
 *   <li><b>BEFORE(PROPAGATE) 실패 시 onFailure 미호출</b>: task가 시작되지 않았으므로 onFailure를 호출하지 않음 (entered
 *       정책만 after로 정리)
 *   <li><b>onSuccess/onFailure는 관측 훅</b>: non-Error 예외는 결과(성공/실패/전파 타입)를 변경하지 않음. 단, onFailure 훅 자체
 *       실패(<b>non-Error</b>)는 원인(cause)에 suppressed로 보존함
 *   <li><b>task Error에도 onFailure best-effort 호출</b>: task가 Error로 실패해도 onFailure는 best-effort로
 *       실행함. 단, onFailure 내부에서 Error 발생 시 즉시 중단함
 *   <li><b>Error는 최우선 전파 대상</b>: 훅 단계에서 Error가 발생하면 우선 전파 대상으로 승격하며, AFTER unwind를 끝까지 시도한 뒤 최종
 *       throw함
 *   <li><b>실패 경로에서 후속 예외는 suppressed로 보존</b>: primary가 존재하면 이후 예외는 suppressed로만 누적함
 *   <li><b>성공 경로에서 AFTER 실패는 primary가 될 수 있음</b>: primary가 없으면 최초 AFTER 예외를 최종 throw 대상으로 설정함
 *   <li><b>outcome은 정책 훅 실패와 무관하게 task 실행 결과만을 의미함</b>: after(outcome, ...)에 전달되는 outcome은 훅/정책 실패를
 *       반영하지 않고, 오직 task.get()의 성공/실패만을 나타냄
 *   <li><b>ThreadLocal depth로 재진입 폭주 fail-fast</b> (MAX_NESTING_DEPTH)
 * </ul>
 *
 * @since 2.4.0
 */
@Slf4j
public class ExecutionPipeline {

  private static final int MAX_NESTING_DEPTH = 32;

  /**
   * V5 Stateless Architecture 검증 완료 (#271):
   *
   * <ul>
   *   <li>용도: Reentrancy 폭주 방지 (fail-fast)
   *   <li>범위: 요청 내 일시적 상태, cross-request 상태 아님
   *   <li>정리: finally 블록에서 depth==0이면 remove() 호출
   *   <li>MDC 전환 불필요: 고빈도 작업, 내부 구현 상세
   * </ul>
   */
  private static final ThreadLocal<Integer> NESTING_DEPTH = ThreadLocal.withInitial(() -> 0);

  private record Slot(ExecutionPolicy policy, FailureMode mode) {}

  private final List<Slot> slots;

  public ExecutionPipeline(List<ExecutionPolicy> policies) {
    Objects.requireNonNull(policies, "policies must not be null");

    List<Slot> temp = new ArrayList<>(policies.size());
    for (int i = 0; i < policies.size(); i++) {
      ExecutionPolicy p = Objects.requireNonNull(policies.get(i), "policies[" + i + "] is null");
      FailureMode mode =
          Objects.requireNonNull(p.failureMode(), "policies[" + i + "].failureMode() is null");
      temp.add(new Slot(p, mode));
    }
    this.slots = List.copyOf(temp);
  }

  /** Supplier 기반 실행 (원본 Throwable 그대로 전파) */
  public <T> T executeRaw(ThrowingSupplier<T> task, TaskContext context) throws Throwable {
    Objects.requireNonNull(task, "task must not be null");
    Objects.requireNonNull(context, "context must not be null");

    // 0) reentrancy guard
    int depth = NESTING_DEPTH.get() + 1;
    NESTING_DEPTH.set(depth);

    if (depth > MAX_NESTING_DEPTH) {
      // 누수 방지 복구
      int prev = depth - 1;
      if (prev <= 0) NESTING_DEPTH.remove();
      else NESTING_DEPTH.set(prev);

      log.error(
          "[Pipeline:REENTRANCY] nesting depth exceeded. depth={}, limit={}, contextType={}, contextHash={}",
          depth,
          MAX_NESTING_DEPTH,
          context.getClass().getSimpleName(),
          System.identityHashCode(context));

      throw new IllegalStateException(
          "ExecutionPipeline nesting depth exceeded ("
              + MAX_NESTING_DEPTH
              + "). Possible recursion loop.");
    }

    final String taskName = safeToTaskName(context);

    try {
      // ========== 변수 선언 ==========
      List<Slot> entered = new ArrayList<>(slots.size());
      ExecutionOutcome taskOutcome = ExecutionOutcome.FAILURE;

      boolean taskStarted = false;
      long taskStartNanos = 0L;
      Long elapsedNanos = null; // null = 미확정

      Throwable primary = null; // 최종 throw 후보
      T result = null;

      // ========== PHASE 1: BEFORE (lifecycle 훅) ==========
      try {
        for (Slot slot : slots) {
          if (invokeBefore(slot, context, taskName)) {
            entered.add(slot); // before 성공한 policy만 entered
          }
        }
      } catch (Throwable t) {
        InterruptUtils.restoreInterruptIfNeeded(t);
        primary = t; // BEFORE PROPAGATE 실패 시 task 미실행
        // onFailure는 BEFORE PROPAGATE 실패 시 호출하지 않음 (PRD 표 8.1)
      }

      // ========== PHASE 2: TASK + ON_FAILURE ==========
      if (primary == null) {
        try {
          taskStarted = true;
          taskStartNanos = System.nanoTime();
          result = task.get();
          elapsedNanos = System.nanoTime() - taskStartNanos;

          // task 성공 직후 outcome 확정 (ON_SUCCESS 전)
          taskOutcome = ExecutionOutcome.SUCCESS;

        } catch (Throwable t) {
          InterruptUtils.restoreInterruptIfNeeded(t);

          // elapsed 계산 (task 실패/Error 포함)
          if (taskStarted && elapsedNanos == null) {
            elapsedNanos = System.nanoTime() - taskStartNanos;
          }
          long elapsed = (elapsedNanos != null) ? elapsedNanos : 0L;

          primary = t; // task 예외를 primary로 설정

          // ON_FAILURE: task Error여도 best-effort로 실행 (PRD 4.5-5 선택 A)
          for (Slot slot : entered) {
            try {
              invokeOnFailure(slot, primary, elapsed, context, taskName);
            } catch (Error err) {
              log.error(
                  "[Pipeline:CRITICAL] Error in onFailure hook. policy={}, taskName={}",
                  policyName(slot.policy()),
                  taskName,
                  err);
              primary = promoteError(primary, err);
              break; // onFailure Error 시 즉시 중단 (PRD 4.5 확장)
            }
          }
        }
      }

      // ========== PHASE 3: ON_SUCCESS (task 성공 시에만) ==========
      if (primary == null && taskOutcome == ExecutionOutcome.SUCCESS) {
        long elapsed = (elapsedNanos != null) ? elapsedNanos : 0L; // 방어 패턴
        for (Slot slot : entered) {
          try {
            invokeOnSuccess(slot, result, elapsed, context, taskName);
          } catch (Error err) {
            log.error(
                "[Pipeline:CRITICAL] Error in onSuccess hook. policy={}, taskName={}",
                policyName(slot.policy()),
                taskName,
                err);
            primary = promoteError(primary, err);
            break; // Error 발생 시 onFailure 스킵, 즉시 after로 (PRD 4.5)
          }
        }
      }

      // ========== PHASE 4: AFTER LIFO (무조건 끝까지 unwind) ==========
      // elapsed 최종 확정 (SG2: 동일 값 전달)
      long elapsed;
      if (elapsedNanos != null) elapsed = elapsedNanos;
      else if (taskStarted) elapsed = System.nanoTime() - taskStartNanos;
      else elapsed = 0L;

      // AFTER: N -> 0 (LIFO, loop는 break하지 않음)
      for (int i = entered.size() - 1; i >= 0; i--) {
        Slot slot = entered.get(i);
        try {
          invokeAfter(slot, taskOutcome, elapsed, context, taskName);
        } catch (Error err) {
          log.error(
              "[Pipeline:CRITICAL] Error in after hook. policy={}, taskName={}",
              policyName(slot.policy()),
              taskName,
              err);
          primary = promoteError(primary, err);
          // Error여도 after unwind 계속 수행 (PRD 4.3)
        } catch (Throwable afterEx) {
          InterruptUtils.restoreInterruptIfNeeded(afterEx);

          if (primary != null) {
            // 실패 경로: after 실패는 suppressed로만 보존
            addSuppressedSafely(primary, afterEx);
          } else {
            // 성공 경로: after 실패가 새로운 Primary
            primary = afterEx;
          }
        }
      }

      // ========== 단일 throw 지점 (메서드 말미, 예외 마스킹 없음) ==========
      if (primary != null) {
        throw primary;
      }
      return result;

    } finally {
      // depth 복구 (반드시 실행)
      int cur = NESTING_DEPTH.get() - 1;
      if (cur <= 0) NESTING_DEPTH.remove();
      else NESTING_DEPTH.set(cur);
    }
  }

  /** Runnable 편의 메서드 */
  public void executeRaw(ThrowingRunnable task, TaskContext context) throws Throwable {
    Objects.requireNonNull(task, "task must not be null");
    executeRaw(
        () -> {
          task.run();
          return null;
        },
        context);
  }

  /**
   * 기존 정책에 추가 정책을 병합한 새로운 Pipeline 생성
   *
   * <p>executeWithFinally() 등에서 동적으로 정책을 추가할 때 사용합니다.
   *
   * <p>기존 Pipeline의 정책 + 추가 정책 순서로 병합됩니다 (BEFORE는 순서대로, AFTER는 역순).
   *
   * @param additionalPolicies 추가할 정책 리스트
   * @return 병합된 정책을 가진 새로운 ExecutionPipeline
   */
  public ExecutionPipeline withAdditionalPolicies(List<ExecutionPolicy> additionalPolicies) {
    Objects.requireNonNull(additionalPolicies, "additionalPolicies must not be null");

    List<ExecutionPolicy> merged = new ArrayList<>(slots.size() + additionalPolicies.size());
    for (Slot s : slots) {
      merged.add(s.policy());
    }
    merged.addAll(additionalPolicies);

    return new ExecutionPipeline(merged);
  }

  private boolean invokeBefore(Slot slot, TaskContext context, String taskName) throws Throwable {
    try {
      slot.policy().before(context);
      return true;
    } catch (Error e) {
      throw e;
    } catch (Throwable t) {
      InterruptUtils.restoreInterruptIfNeeded(t);
      log.warn(
          "[Policy:BEFORE] failed. policy={}, taskName={}", policyName(slot.policy()), taskName, t);

      if (slot.mode() == FailureMode.PROPAGATE) {
        throw t;
      }
      return false;
    }
  }

  private <T> void invokeOnSuccess(
      Slot slot, T result, long elapsedNanos, TaskContext context, String taskName) {
    try {
      slot.policy().onSuccess(result, elapsedNanos, context);
    } catch (Error err) {
      throw err;
    } catch (Throwable t) {
      InterruptUtils.restoreInterruptIfNeeded(t);
      log.warn(
          "[Policy:ON_SUCCESS] failed. policy={}, taskName={}",
          policyName(slot.policy()),
          taskName,
          t);
      // non-Error는 always swallow
    }
  }

  private void invokeOnFailure(
      Slot slot, Throwable cause, long elapsedNanos, TaskContext context, String taskName) {
    if (cause == null) return;

    try {
      slot.policy().onFailure(cause, elapsedNanos, context);
    } catch (Error err) {
      throw err;
    } catch (Throwable t) {
      InterruptUtils.restoreInterruptIfNeeded(t);
      log.warn(
          "[Policy:ON_FAILURE] failed. policy={}, taskName={}",
          policyName(slot.policy()),
          taskName,
          t);
      // 관측 훅 실패는 원인 추적 위해 cause에 suppressed로 보존
      addSuppressedSafely(cause, t);
    }
  }

  private void invokeAfter(
      Slot slot, ExecutionOutcome outcome, long elapsedNanos, TaskContext context, String taskName)
      throws Throwable {
    try {
      slot.policy().after(outcome, elapsedNanos, context);
    } catch (Error err) {
      throw err;
    } catch (Throwable t) {
      InterruptUtils.restoreInterruptIfNeeded(t);
      log.warn(
          "[Policy:AFTER] failed. policy={}, taskName={}", policyName(slot.policy()), taskName, t);

      if (slot.mode() == FailureMode.PROPAGATE) {
        throw t;
      }
      // SWALLOW
    }
  }

  private Throwable promoteError(Throwable currentPrimary, Error newError) {
    if (currentPrimary == null) return newError;

    if (currentPrimary instanceof Error currentError) {
      addSuppressedSafely(currentError, newError);
      return currentError; // 최초 Error 유지
    }

    addSuppressedSafely(newError, currentPrimary);
    return newError;
  }

  private void addSuppressedSafely(Throwable primary, Throwable suppressed) {
    if (primary == null || suppressed == null) return;
    if (primary == suppressed) return;

    try {
      primary.addSuppressed(suppressed);
    } catch (Exception e) {
      log.debug(
          "addSuppressed failed. primary={}, suppressed={}",
          primary.getClass().getName(),
          suppressed.getClass().getName(),
          e);
    }
  }

  private String policyName(ExecutionPolicy policy) {
    return policy != null ? policy.getClass().getSimpleName() : "null";
  }

  private String safeToTaskName(TaskContext context) {
    if (context == null) return "unknown";
    try {
      return String.valueOf(context.toTaskName());
    } catch (Throwable t) {
      return "unknown";
    }
  }
}
