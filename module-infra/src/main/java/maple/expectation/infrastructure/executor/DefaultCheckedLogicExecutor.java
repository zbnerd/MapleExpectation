package maple.expectation.infrastructure.executor;

import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.function.CheckedRunnable;
import maple.expectation.infrastructure.executor.function.CheckedSupplier;
import maple.expectation.infrastructure.executor.policy.ExecutionPipeline;
import maple.expectation.util.InterruptUtils;

/**
 * CheckedLogicExecutor의 기본 구현체
 *
 * <p>ExecutionPipeline 기반으로 checked 예외를 처리하며, try-catch 없이 예외 변환을 템플릿 내부로 중앙화합니다.
 *
 * <h3>핵심 계약 (ADR)</h3>
 *
 * <ul>
 *   <li><b>Error 즉시 전파</b>: VirtualMachineError 등은 매핑/복구 없이 즉시 throw
 *   <li><b>RuntimeException 통과</b>: 이미 unchecked이므로 그대로 throw
 *   <li><b>Exception → mapper 변환</b>: checked 예외만 mapper로 RuntimeException 변환
 *   <li><b>mapper 계약 방어</b>: null 반환, 계약 위반 시 IllegalStateException
 *   <li><b>suppressed 이관</b>: Exception→RuntimeException 변환 시 suppressed 복사
 *   <li><b>인터럽트 플래그 복원</b>: InterruptedException 발생 시 Thread.currentThread().interrupt()
 * </ul>
 *
 * @since 2.4.0
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultCheckedLogicExecutor implements CheckedLogicExecutor {

  private final ExecutionPipeline pipeline;

  // ========================================
  // Level 2: throws 전파 (상위에서 처리)
  // ========================================

  @Override
  public <T> T execute(CheckedSupplier<T> task, TaskContext context) throws Exception {
    Objects.requireNonNull(task, "task must not be null");
    Objects.requireNonNull(context, "context must not be null");

    try {
      // 명시적 람다로 오버로드 추론 문제 방지
      return pipeline.executeRaw(() -> task.get(), context);
    } catch (Error e) {
      throw e;
    } catch (RuntimeException re) {
      // RuntimeException도 cause chain에 InterruptedException이 있으면 복원
      InterruptUtils.restoreInterruptIfNeeded(re);
      throw re;
    } catch (Exception e) {
      InterruptUtils.restoreInterruptIfNeeded(e);
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(
          "Unexpected Throwable (not Error/Exception): " + t.getClass().getName(), t);
    }
  }

  // ========================================
  // Level 1: checked → runtime 변환 (try-catch 제거)
  // ========================================

  @Override
  public <T> T executeUnchecked(
      CheckedSupplier<T> task,
      TaskContext context,
      Function<Exception, ? extends RuntimeException> mapper) {
    Objects.requireNonNull(task, "task must not be null");
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(mapper, "mapper must not be null");

    try {
      // 명시적 람다로 오버로드 추론 문제 방지
      return pipeline.executeRaw(() -> task.get(), context);
    } catch (Throwable t) {
      // Error → 즉시 throw (mapper 미호출)
      if (t instanceof Error e) throw e;

      // RuntimeException → 그대로 throw (이미 unchecked)
      // cause chain에 InterruptedException이 있으면 복원
      if (t instanceof RuntimeException re) {
        InterruptUtils.restoreInterruptIfNeeded(re);
        throw re;
      }

      // Exception이 아닌 Throwable → 계약 위반 (예: custom Throwable subclass)
      if (!(t instanceof Exception ex)) {
        throw new IllegalStateException(
            "Task threw non-Exception Throwable: " + t.getClass().getName(), t);
      }

      // Exception → mapper로 변환
      InterruptUtils.restoreInterruptIfNeeded(ex);
      throw applyMapper(mapper, ex);
    }
  }

  // ========================================
  // Level 1 + finally: 자원 해제 보장
  // ========================================

  /**
   * {@inheritDoc}
   *
   * <h4>ADR: finalizer 중복 등록 금지</h4>
   *
   * <p>이 메서드는 자체 try-finally로 finalizer를 1회 실행합니다. 동일한 finalizer를 {@link
   * maple.expectation.infrastructure.executor.policy.FinallyPolicy}에 중복 등록하면 2회 실행되어 예기치 않은 동작이 발생할
   * 수 있습니다.
   */
  @Override
  public <T> T executeWithFinallyUnchecked(
      CheckedSupplier<T> task,
      CheckedRunnable finalizer,
      TaskContext context,
      Function<Exception, ? extends RuntimeException> mapper) {
    Objects.requireNonNull(task, "task must not be null");
    Objects.requireNonNull(finalizer, "finalizer must not be null");
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(mapper, "mapper must not be null");

    T result = null;
    Throwable primary = null;

    try {
      // 명시적 람다로 오버로드 추론 문제 방지
      result = pipeline.executeRaw(() -> task.get(), context);
    } catch (Throwable t) {
      primary = t;
    } finally {
      try {
        finalizer.run();
      } catch (Throwable ft) {
        // finalizer Error는 즉시 throw, task primary는 suppressed로 보존 (P1-6)
        if (ft instanceof Error e) {
          if (primary != null) {
            safeAddSuppressed(e, primary);
          }
          throw e;
        }

        if (primary == null) {
          primary = ft;
        } else {
          safeAddSuppressed(primary, ft);
        }
      }
    }

    // 정상 완료
    if (primary == null) {
      return result;
    }

    // Error → 즉시 throw
    if (primary instanceof Error e) throw e;

    // RuntimeException → 그대로 throw
    // cause chain에 InterruptedException이 있으면 복원
    if (primary instanceof RuntimeException re) {
      InterruptUtils.restoreInterruptIfNeeded(re);
      throw re;
    }

    // Exception → mapper 변환 + suppressed 이관
    if (primary instanceof Exception ex) {
      InterruptUtils.restoreInterruptIfNeeded(ex);
      RuntimeException mapped = applyMapper(mapper, ex);
      copySuppressed(ex, mapped);
      throw mapped;
    }

    // Throwable (비-Exception) → 계약 위반
    throw new IllegalStateException(
        "Task threw non-Exception Throwable: " + primary.getClass().getName(), primary);
  }

  // ========================================
  // Private Helpers
  // ========================================

  /**
   * mapper 계약 방어 (단일 진실원)
   *
   * <p>mapper가 계약을 위반하면 IllegalStateException을 throw합니다:
   *
   * <ul>
   *   <li>null 반환 → IllegalStateException
   *   <li>Error throw → 그대로 throw
   *   <li>RuntimeException throw → 그대로 throw
   *   <li>기타 Throwable throw → IllegalStateException
   * </ul>
   */
  private RuntimeException applyMapper(
      Function<Exception, ? extends RuntimeException> mapper, Exception ex) {
    try {
      RuntimeException mapped = mapper.apply(ex);
      if (mapped == null) {
        throw new IllegalStateException(
            "Exception mapper returned null for: " + ex.getClass().getName());
      }
      return mapped;
    } catch (Error e) {
      throw e;
    } catch (RuntimeException re) {
      throw re;
    } catch (Throwable mt) {
      throw new IllegalStateException(
          "Exception mapper violated contract (threw non-RuntimeException): "
              + mt.getClass().getName(),
          mt);
    }
  }

  /**
   * suppressed 예외 이관
   *
   * <p>from에 누적된 suppressed 예외들을 to로 복사합니다. cleanup 실패 정보가 유실되지 않도록 합니다.
   */
  private static void copySuppressed(Throwable from, Throwable to) {
    for (Throwable s : from.getSuppressed()) {
      safeAddSuppressed(to, s);
    }
  }

  /**
   * 안전한 suppressed 추가
   *
   * <p>자기 자신을 suppressed로 추가하려는 경우를 방어합니다.
   */
  private static void safeAddSuppressed(Throwable primary, Throwable suppressed) {
    if (primary != suppressed && suppressed != null) {
      try {
        primary.addSuppressed(suppressed);
      } catch (Throwable ignored) {
        // addSuppressed 실패는 무시 (예: suppressed 비활성화된 예외)
      }
    }
  }
}
