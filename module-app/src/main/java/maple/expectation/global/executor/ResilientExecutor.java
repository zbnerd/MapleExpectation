package maple.expectation.global.executor;

import java.util.function.Function;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.strategy.ExceptionTranslator;

/**
 * Resilient execution pattern interface.
 *
 * <p>Provides advanced error handling with translation and fallback capabilities. Part of Interface
 * Segregation Principle (ISP) compliance - clients only depend on methods they use.
 */
public interface ResilientExecutor {

  /**
   * Execute task with exception translation.
   *
   * @param <T> result type
   * @param task task to execute
   * @param translator exception translation strategy
   * @param context execution context
   * @return task result
   */
  <T> T executeWithTranslation(
      ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context);

  /**
   * Execute task with fallback on exception.
   *
   * @param <T> result type
   * @param task task to execute
   * @param fallback fallback function
   * @param context execution context
   * @return task result or fallback result
   */
  <T> T executeWithFallback(
      ThrowingSupplier<T> task, Function<Throwable, T> fallback, TaskContext context);

  /**
   * Execute task with translated exception recovery.
   *
   * @param <T> result type
   * @param task task to execute
   * @param recovery recovery function receiving translated exception
   * @param context execution context
   * @return task result or recovery result
   */
  <T> T executeOrCatch(
      ThrowingSupplier<T> task, Function<Throwable, T> recovery, TaskContext context);
}
