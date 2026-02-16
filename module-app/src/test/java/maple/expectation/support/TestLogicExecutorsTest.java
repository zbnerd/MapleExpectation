package maple.expectation.support;

import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test class to verify TestLogicExecutors utility works correctly.
 */
@DisplayName("TestLogicExecutors Utility Test")
class TestLogicExecutorsTest {

  @Test
  @DisplayName("passThrough() should create a working LogicExecutor mock")
  void passThrough_shouldCreateWorkingMock() {
    // Given: Create a pass-through executor
    LogicExecutor executor = TestLogicExecutors.passThrough();

    // When & Then: Execute a simple task
    String result = executor.execute(
        () -> "test-result",
        TaskContext.of("Test", "SimpleTask")
    );

    assertThat(result).isEqualTo("test-result");
  }

  @Test
  @DisplayName("passThrough() should handle exceptions correctly")
  void passThrough_shouldHandleExceptions() {
    // Given: Create a pass-through executor
    LogicExecutor executor = TestLogicExecutors.passThrough();

    // When & Then: Execute a task that throws an exception
    RuntimeException exception = assertThrows(
        RuntimeException.class,
        () -> executor.execute(
            (ThrowingSupplier<String>) () -> { throw new RuntimeException("test-exception"); },
            TaskContext.of("Test", "ExceptionTask")
        )
    );

    assertThat(exception).hasMessage("test-exception");
  }

  @Test
  @DisplayName("All 6 methods should work correctly")
  void allMethods_shouldWork() {
    // Given: Create a pass-through executor
    LogicExecutor executor = TestLogicExecutors.passThrough();

    // Pattern 1: Execute with exception propagation
    String result1 = executor.execute(
        () -> "execute-result",
        TaskContext.of("Test", "Execute")
    );
    assertThat(result1).isEqualTo("execute-result");

    // Pattern 2: Execute with default value
    String result2 = executor.executeOrDefault(
        () -> "should-not-use-default",
        "default-value",
        TaskContext.of("Test", "ExecuteOrDefault")
    );
    assertThat(result2).isEqualTo("should-not-use-default");

    // Pattern 3: Execute with exception and default value
    String result3 = executor.executeOrDefault(
        () -> { throw new RuntimeException(); },
        "default-on-error",
        TaskContext.of("Test", "ExecuteOrDefaultWithDefault")
    );
    assertThat(result3).isEqualTo("default-on-error");

    // Pattern 4: Execute with finally block
    String result4 = executor.executeWithFinally(
        () -> "finally-result",
        () -> System.out.println("Finally block executed"),
        TaskContext.of("Test", "ExecuteWithFinally")
    );
    assertThat(result4).isEqualTo("finally-result");

    // Pattern 5: Execute or catch with recovery
    String result5 = executor.executeOrCatch(
        (ThrowingSupplier<String>) () -> { throw new RuntimeException("original"); },
        (Throwable t) -> "recovered",
        TaskContext.of("Test", "ExecuteOrCatch")
    );
    assertThat(result5).isEqualTo("recovered");

  
    // Pattern 8: Execute with fallback (original exception)
    String result8 = executor.executeWithFallback(
        (ThrowingSupplier<String>) () -> { throw new RuntimeException("original"); },
        (Throwable t) -> "fallback-result",
        TaskContext.of("Test", "ExecuteWithFallback")
    );
    assertThat(result8).isEqualTo("fallback-result");

    // Pattern 1: Void execution
    executor.executeVoid(
        (ThrowingRunnable) () -> System.out.println("Void executed"),
        TaskContext.of("Test", "ExecuteVoid")
    );
  }
}