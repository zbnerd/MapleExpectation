package maple.expectation.global.executor;

import maple.expectation.global.common.function.ThrowingSupplier;

/**
 * Safe execution pattern interface.
 *
 * <p>Provides fallback capabilities for error recovery. Part of Interface Segregation
 * Principle (ISP) compliance - clients only depend on methods they use.
 */
public interface SafeExecutor {

  /**
   * Execute task with default value on exception.
   *
   * @param <T> result type
   * @param task task to execute
   * @param defaultValue default value on exception
   * @param context execution context
   * @return task result or default
   */
  <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, TaskContext context);
}
