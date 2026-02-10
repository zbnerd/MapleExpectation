package maple.expectation.global.executor;

import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.function.ThrowingRunnable;

/**
 * Basic execution pattern interface.
 *
 * <p>Provides core execution methods without fallback or translation capabilities. Part of
 * Interface Segregation Principle (ISP) compliance - clients only depend on methods they use.
 */
public interface BasicExecutor {

  /**
   * Execute task with exception propagation.
   *
   * @param <T> result type
   * @param task task to execute
   * @param context execution context
   * @return task result
   */
  <T> T execute(ThrowingSupplier<T> task, TaskContext context);

  /**
   * Execute void task with exception propagation.
   *
   * @param task task to execute
   * @param context execution context
   */
  void executeVoid(ThrowingRunnable task, TaskContext context);

  /**
   * Execute task with explicit finally block.
   *
   * @param <T> result type
   * @param task task to execute
   * @param finallyBlock cleanup to run
   * @param context execution context
   * @return task result
   */
  <T> T executeWithFinally(ThrowingSupplier<T> task, Runnable finallyBlock, TaskContext context);
}
