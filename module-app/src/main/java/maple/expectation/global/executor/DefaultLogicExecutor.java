package maple.expectation.global.executor;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.global.executor.policy.ExecutionPipeline;
import maple.expectation.global.executor.policy.FinallyPolicy;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.springframework.stereotype.Component;

/**
 * ExecutionPipeline 기반 LogicExecutor 구현체
 *
 * <p>PRD v4 섹션 13.1 / 14 준수:
 *
 * <ul>
 *   <li><b>Pipeline executeRaw() 호출</b>: 모든 메서드가 ExecutionPipeline을 통해 실행
 *   <li><b>Error 즉시 rethrow</b>: VirtualMachineError 등은 번역 없이 전파
 *   <li><b>SG1 적용</b>: executeWithRecovery는 execute() 재사용 금지, executeRaw() 직접 호출
 *   <li><b>호출부 0 수정</b>: 기존 메서드 시그니처 유지, 내부 구현만 교체
 * </ul>
 *
 * @since 2.4.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultLogicExecutor implements LogicExecutor {

  private static final String UNEXPECTED_TRANSLATOR_FAILURE =
      "Translator failed with unexpected Throwable";

  private final ExecutionPipeline pipeline;
  private final ExceptionTranslator translator;

  @Override
  public <T> T execute(ThrowingSupplier<T> task, TaskContext context) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(context, "context");

    try {
      return pipeline.executeRaw(task, context);
    } catch (Error e) {
      throw e;
    } catch (Throwable t) {
      Throwable primary = translatePrimary(t, context);
      throwAsUnchecked(primary);
      return unreachable();
    }
  }

  @Override
  public <T> T executeOrCatch(
      ThrowingSupplier<T> task, Function<Throwable, T> recovery, TaskContext context) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(recovery, "recovery");
    Objects.requireNonNull(context, "context");

    try {
      // SG1: execute() 재사용 금지, executeRaw 직접 호출
      return pipeline.executeRaw(task, context);
    } catch (Error e) {
      throw e;
    } catch (Throwable t) {
      // 호환성: 기존에는 execute() 경유로 "번역된 예외"가 recovery에 전달되었음.
      // translator가 실패해도 (RuntimeException이라면) 그 예외 자체를 recovery에 넘긴다.
      Throwable forRecovery = translateForRecovery(t, context);
      return recovery.apply(forRecovery);
    }
  }

  @Override
  public <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, TaskContext context) {
    return executeOrCatch(task, e -> defaultValue, context);
  }

  @Override
  public void executeVoid(ThrowingRunnable task, TaskContext context) {
    Objects.requireNonNull(task, "task");
    execute(
        () -> {
          task.run();
          return null;
        },
        context);
  }

  @Override
  public <T> T executeWithFinally(
      ThrowingSupplier<T> task, Runnable finallyBlock, TaskContext context) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(finallyBlock, "finallyBlock");
    Objects.requireNonNull(context, "context");

    // 목적:
    // 1) Finally를 정책(after)로 모델링하여 PRD v4 unwind 규약(suppressed 포함)에 합류
    // 2) 다만 BEFORE 단계에서 PROPAGATE로 터져 FinallyPolicy가 entered 못한 케이스까지 "정확히 1회" 실행 보장
    final AtomicBoolean ran = new AtomicBoolean(false);

    Runnable onceFinally =
        () -> {
          if (ran.compareAndSet(false, true)) {
            finallyBlock.run();
          }
        };

    ExecutionPipeline withFinally =
        pipeline.withAdditionalPolicies(List.of(new FinallyPolicy(onceFinally)));

    // 방어: withAdditionalPolicies가 원본을 mutate하는 구현이면, 정책이 호출마다 누적될 수 있다.
    // (정상은 "새 인스턴스 반환"이어야 함)
    if (withFinally == pipeline) {
      log.warn(
          "ExecutionPipeline.withAdditionalPolicies returned same instance. "
              + "If pipeline is mutable, FinallyPolicy may accumulate across calls.");
    }

    try {
      return withFinally.executeRaw(task, context);
    } catch (Error e) {
      // Error도 "가능하면 정리"는 시도하되, Error를 덮어쓰지 않는다.
      runCleanupSuppressing(e, onceFinally);
      throw e;
    } catch (Throwable t) {
      Throwable primary = translatePrimary(t, context);
      runCleanupSuppressing(primary, onceFinally);
      throwAsUnchecked(primary);
      return unreachable();
    }
  }

  @Override
  public <T> T executeWithTranslation(
      ThrowingSupplier<T> task, ExceptionTranslator customTranslator, TaskContext context) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(customTranslator, "customTranslator");
    Objects.requireNonNull(context, "context");

    try {
      return pipeline.executeRaw(task, context);
    } catch (Error e) {
      throw e;
    } catch (Throwable t) {
      Throwable primary = translateSafe(customTranslator, t, context);
      throwAsUnchecked(primary);
      return unreachable();
    }
  }

  @Override
  public <T> T executeWithFallback(
      ThrowingSupplier<T> task, Function<Throwable, T> fallback, TaskContext context) {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(fallback, "fallback");
    Objects.requireNonNull(context, "context");

    try {
      return pipeline.executeRaw(task, context);
    } catch (Error e) {
      throw e;
    } catch (Throwable t) {
      return fallback.apply(t);
    }
  }

  /**
   * translator를 통해 "던질 primary"를 만든다. - 정상: RuntimeException 반환 - translator가 RuntimeException으로
   * 실패: 그 예외 자체를 primary로 삼는다. - translator가 Error로 실패: Error를 primary로 삼는다. - 계약 위반(Throwable):
   * IllegalStateException으로 래핑하여 primary로 삼는다.
   */
  /**
   * 임의의 translator를 안전하게 호출한다 (executeWithTranslation 전용). translatePrimary와 동일한 안전 가드를 적용하되, 주입된
   * 기본 translator 대신 커스텀 translator를 사용한다.
   */
  private static Throwable translateSafe(
      ExceptionTranslator customTranslator, Throwable t, TaskContext context) {
    try {
      return customTranslator.translate(t, context);
    } catch (RuntimeException | Error ex) {
      return ex;
    } catch (Throwable unexpected) {
      return new IllegalStateException(UNEXPECTED_TRANSLATOR_FAILURE, unexpected);
    }
  }

  private Throwable translatePrimary(Throwable t, TaskContext context) {
    try {
      return translator.translate(t, context); // expected: RuntimeException
    } catch (RuntimeException | Error ex) {
      return ex;
    } catch (Throwable unexpected) {
      return new IllegalStateException(UNEXPECTED_TRANSLATOR_FAILURE, unexpected);
    }
  }

  /**
   * recovery에 넘길 throwable을 만든다. - 기존 동작 호환성: execute()를 경유했을 때 recovery는 "번역된 RuntimeException"을
   * 받았다. - translator가 RuntimeException으로 실패하면: 그 예외 자체를 recovery에 전달한다. - translator가 Error로 실패하면:
   * Error는 복구 대상이 아니므로 전파한다. - 계약 위반(Throwable): IllegalStateException으로 감싸 recovery에 전달한다.
   */
  private Throwable translateForRecovery(Throwable t, TaskContext context) {
    try {
      return translator.translate(t, context);
    } catch (RuntimeException ex) {
      return ex;
    } catch (Error e) {
      throw e;
    } catch (Throwable unexpected) {
      return new IllegalStateException(UNEXPECTED_TRANSLATOR_FAILURE, unexpected);
    }
  }

  /** 정리 작업을 1회만 실행하고, 정리 중 예외가 나와도 primary를 덮지 않고 suppressed로만 합류시킨다. */
  private static void runCleanupSuppressing(Throwable primary, Runnable onceFinally) {
    try {
      onceFinally.run(); // 이미 실행됐으면 no-op
    } catch (Throwable cleanupEx) {
      safeAddSuppressed(primary, cleanupEx);
    }
  }

  private static void safeAddSuppressed(Throwable primary, Throwable suppressed) {
    if (primary == null || suppressed == null) return;
    if (primary == suppressed) return;
    try {
      primary.addSuppressed(suppressed);
    } catch (Exception ignore) {
      // suppressed 추가 실패는 실행 흐름을 깨지 않는다 (Error는 전파)
    }
  }

  private static void throwAsUnchecked(Throwable t) {
    if (t instanceof Error e) throw e;
    if (t instanceof RuntimeException re) throw re;
    // translatePrimary/translateForRecovery 설계상 여기로 오면 안 된다.
    throw new IllegalStateException("Unexpected checked throwable", t);
  }

  private static <T> T unreachable() {
    return null;
  }
}
