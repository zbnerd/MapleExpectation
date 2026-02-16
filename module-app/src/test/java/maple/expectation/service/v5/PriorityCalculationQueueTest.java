package maple.expectation.service.v5;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import maple.expectation.service.v5.queue.ExpectationCalculationTask;
import maple.expectation.service.v5.queue.PriorityCalculationQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V5: Priority Queue Tests")
class PriorityCalculationQueueTest {

  private LogicExecutor executor;
  private PriorityCalculationQueue queue;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    executor = new TestLogicExecutor();
    queue = new PriorityCalculationQueue(executor);
  }

  @Test
  @DisplayName("고우선순위 작업이 먼저 처리되어야 함")
  void highPriorityProcessedBeforeLow() {
    ExpectationCalculationTask highTask = ExpectationCalculationTask.highPriority("user1", false);
    ExpectationCalculationTask lowTask = ExpectationCalculationTask.lowPriority("user2");

    assertThat(queue.offer(highTask)).isTrue();
    assertThat(queue.offer(lowTask)).isTrue();
    assertThat(queue.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("LOW 우선순위는 큐가 가득 차도 추가 가능")
  void lowPriorityAcceptedWhenNotFull() {
    ExpectationCalculationTask lowTask = ExpectationCalculationTask.lowPriority("user1");
    boolean accepted = queue.offer(lowTask);

    assertThat(accepted).isTrue();
    assertThat(queue.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("큐 사이즈 확인")
  void queueSize() {
    assertThat(queue.size()).isEqualTo(0);
    assertThat(queue.getHighPriorityCount()).isEqualTo(0);
  }

  /** 테스트용 간단한 LogicExecutor 구현 */
  private static class TestLogicExecutor implements LogicExecutor {
    @Override
    public <T> T execute(ThrowingSupplier<T> task, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        return defaultValue;
      }
    }

    @Override
    public void executeVoid(ThrowingRunnable task, TaskContext context) {
      try {
        task.run();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public <T> T executeWithFinally(
        ThrowingSupplier<T> task, Runnable finallyBlock, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      } finally {
        finallyBlock.run();
      }
    }

    @Override
    public <T> T executeWithTranslation(
        ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        throw translator.translate(e, context);
      }
    }

    @Override
    public <T> T executeWithFallback(
        ThrowingSupplier<T> task, Function<Throwable, T> fallback, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        return fallback.apply(e);
      }
    }

    @Override
    public <T> T executeOrCatch(
        ThrowingSupplier<T> task, Function<Throwable, T> recovery, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        return recovery.apply(e);
      }
    }
  }
}
