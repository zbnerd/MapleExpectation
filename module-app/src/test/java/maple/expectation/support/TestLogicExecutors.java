package maple.expectation.support;

import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Test utility for creating LogicExecutor mocks that delegate to actual execution.
 *
 * <p>Eliminates ~800 lines of duplicate mock boilerplate across 35+ test files by providing a
 * simple pass-through implementation that directly executes tasks without mocking behavior.
 *
 * <h3>Usage Example</h3>
 *
 * <pre>{@code
 * // Before: ~20 lines of mock setup
 * LogicExecutor executor = Mockito.mock(LogicExecutor.class);
 * Mockito.lenient().when(executor.execute(
 *     Mockito.any(), Mockito.any()
 * )).thenAnswer(inv -> {
 *     ThrowingSupplier<?> task = inv.getArgument(0);
 *     return task.get();
 * });
 *
 * // After: 1 line
 * LogicExecutor executor = TestLogicExecutors.passThrough();
 * }</pre>
 *
 * <p>The passThrough() mock uses lenient() to prevent unnecessary strictness in tests and delegates
 * all method calls to actual task execution, making it ideal for testing components that depend on
 * LogicExecutor without interfering with task logic itself.
 */
public final class TestLogicExecutors {

  private TestLogicExecutors() {
    // Utility class - prevent instantiation
  }

  /**
   * Creates a LogicExecutor mock that directly executes all tasks.
   *
   * <p>Each method is mocked with lenient() and delegates to actual task execution, making it ideal
   * for testing components that depend on LogicExecutor without interfering with task logic itself.
   *
   * @return LogicExecutor mock that passes through all method calls to actual execution
   */
  /**
   * Creates a plain LogicExecutor mock for tests that need to stub specific return values.
   *
   * <p>Use this when your test needs to control what the executor returns (e.g., testing service
   * behavior with mocked calculator results). For most cases where you want actual execution, use
   * {@link #passThrough()} instead.
   *
   * @return LogicExecutor mock that can be stubbed with when().thenReturn()
   */
  public static LogicExecutor mock() {
    return Mockito.mock(LogicExecutor.class);
  }

  public static LogicExecutor passThrough() {
    LogicExecutor mock = Mockito.mock(LogicExecutor.class);

    // Pattern 1 & 2: Execute with exception propagation
    Mockito.lenient()
        .when(
            mock.execute(
                ArgumentMatchers.<ThrowingSupplier<Object>>any(),
                ArgumentMatchers.<TaskContext>any()))
        .thenAnswer(
            invocation -> {
              ThrowingSupplier<Object> task = invocation.getArgument(0);
              return task.get();
            });

    // Pattern 3 & 4: Execute with default value
    Mockito.lenient()
        .when(
            mock.executeOrDefault(
                ArgumentMatchers.<ThrowingSupplier<Object>>any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.<TaskContext>any()))
        .thenAnswer(
            invocation -> {
              ThrowingSupplier<Object> task = invocation.getArgument(0);
              Object defaultValue = invocation.getArgument(1);
              try {
                return task.get();
              } catch (Throwable t) {
                return defaultValue;
              }
            });

    // Pattern 1: Void execution
    Mockito.lenient()
        .doAnswer(
            invocation -> {
              ThrowingRunnable task = invocation.getArgument(0);
              task.run();
              return null;
            })
        .when(mock)
        .executeVoid(ArgumentMatchers.<ThrowingRunnable>any(), ArgumentMatchers.<TaskContext>any());

    // Pattern 1: Execute with finally block
    Mockito.lenient()
        .when(
            mock.executeWithFinally(
                ArgumentMatchers.<ThrowingSupplier<Object>>any(),
                ArgumentMatchers.<Runnable>any(),
                ArgumentMatchers.<TaskContext>any()))
        .thenAnswer(
            invocation -> {
              ThrowingSupplier<Object> task = invocation.getArgument(0);
              Runnable finallyBlock = invocation.getArgument(1);
              try {
                return task.get();
              } finally {
                finallyBlock.run();
              }
            });

    // Pattern 6: Execute with translation
    Mockito.lenient()
        .when(
            mock.executeWithTranslation(
                ArgumentMatchers.<ThrowingSupplier<Object>>any(),
                ArgumentMatchers.<ExceptionTranslator>any(),
                ArgumentMatchers.<TaskContext>any()))
        .thenAnswer(
            invocation -> {
              ThrowingSupplier<Object> task = invocation.getArgument(0);
              ExceptionTranslator translator = invocation.getArgument(1);
              try {
                return task.get();
              } catch (Throwable t) {
                throw translator.translate(t, invocation.getArgument(2));
              }
            });

    // Pattern 8: Execute with fallback (original exception)
    Mockito.lenient()
        .when(
            mock.executeWithFallback(
                ArgumentMatchers.<ThrowingSupplier<Object>>any(),
                ArgumentMatchers.<java.util.function.Function<Throwable, Object>>any(),
                ArgumentMatchers.<TaskContext>any()))
        .thenAnswer(
            invocation -> {
              ThrowingSupplier<Object> task = invocation.getArgument(0);
              java.util.function.Function<Throwable, Object> fallback = invocation.getArgument(1);
              try {
                return task.get();
              } catch (Throwable t) {
                return fallback.apply(t);
              }
            });

    // Pattern 5: Execute or catch with recovery
    Mockito.lenient()
        .when(
            mock.executeOrCatch(
                ArgumentMatchers.<ThrowingSupplier<Object>>any(),
                ArgumentMatchers.<java.util.function.Function<Throwable, Object>>any(),
                ArgumentMatchers.<TaskContext>any()))
        .thenAnswer(
            invocation -> {
              ThrowingSupplier<Object> task = invocation.getArgument(0);
              java.util.function.Function<Throwable, Object> recovery = invocation.getArgument(1);
              try {
                return task.get();
              } catch (Throwable t) {
                // Note: Uses default translator to get translated exception
                return recovery.apply(t);
              }
            });

    return mock;
  }
}
