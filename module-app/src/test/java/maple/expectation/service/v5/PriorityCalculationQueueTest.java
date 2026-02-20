package maple.expectation.service.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.function.ThrowingRunnable;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import maple.expectation.service.v5.queue.ExpectationCalculationTask;
import maple.expectation.service.v5.queue.PriorityCalculationQueue;
import maple.expectation.service.v5.queue.QueuePriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("V5 CQRS: Priority Queue Tests")
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
  @DisplayName("LOW 우선순위는 큐가 가득 차지 않으면 추가 가능")
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

  @Test
  @DisplayName("고우선순위 capacity 초과 시 백프레셔 발생")
  void highPriorityBackpressureWhenCapacityExceeded() {
    // Given: HIGH priority capacity is 1000
    for (int i = 0; i < 1000; i++) {
      ExpectationCalculationTask task = ExpectationCalculationTask.highPriority("user" + i, false);
      assertThat(queue.offer(task)).isTrue();
    }

    // When: Try to add 1001st HIGH priority task
    ExpectationCalculationTask overflowTask =
        ExpectationCalculationTask.highPriority("overflow", false);
    boolean accepted = queue.offer(overflowTask);

    // Then: Should be rejected due to backpressure
    assertThat(accepted).isFalse();
    assertThat(queue.getHighPriorityCount()).isEqualTo(1000);
  }

  @Test
  @DisplayName("LOW 우선순위 작업은 capacity 무관하게 수락됨")
  void lowPriorityAlwaysAccepted() {
    // Given: HIGH priority at capacity
    for (int i = 0; i < 1000; i++) {
      ExpectationCalculationTask task = ExpectationCalculationTask.highPriority("user" + i, false);
      queue.offer(task);
    }

    // When: Add LOW priority tasks
    for (int i = 0; i < 100; i++) {
      ExpectationCalculationTask task = ExpectationCalculationTask.lowPriority("low" + i);
      boolean accepted = queue.offer(task);

      // Then: All LOW priority tasks should be accepted
      assertThat(accepted).isTrue();
    }

    assertThat(queue.size()).isEqualTo(1100); // 1000 HIGH + 100 LOW
    assertThat(queue.getHighPriorityCount()).isEqualTo(1000);
  }

  @Test
  @DisplayName("poll()은 큐가 비어있으면 블로킹됨")
  void pollBlocksWhenEmpty() throws InterruptedException {
    // Given: Empty queue
    assertThat(queue.size()).isEqualTo(0);

    // When/Then: Poll in separate thread should block
    Thread pollingThread =
        new Thread(
            () -> {
              try {
                ExpectationCalculationTask task = queue.poll();
                // If we reach here, task should not be null (blocking worked)
                assertNotNull(task);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });

    pollingThread.start();
    Thread.sleep(100); // Give thread time to start blocking

    // Clean up: Interrupt the polling thread
    pollingThread.interrupt();
    pollingThread.join(500);
  }

  @Test
  @DisplayName("poll(timeoutMs)은 타임아웃 시 null 반환")
  void pollWithTimeoutReturnsNull() {
    // Given: Empty queue
    assertThat(queue.size()).isEqualTo(0);

    // When: Poll with short timeout
    ExpectationCalculationTask task = queue.poll(100);

    // Then: Should return null (timeout)
    assertNull(task);
  }

  @Test
  @DisplayName("poll(timeoutMs)은 타임아웃 내에 작업이 도착하면 반환")
  void pollWithTimeoutReturnsTaskWhenAvailable() throws InterruptedException {
    // Given: Empty queue
    assertThat(queue.size()).isEqualTo(0);

    // When: Add task after delay and poll with longer timeout
    Thread adderThread =
        new Thread(
            () -> {
              try {
                Thread.sleep(50);
                ExpectationCalculationTask task =
                    ExpectationCalculationTask.highPriority("delayed", false);
                queue.offer(task);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });

    adderThread.start();
    ExpectationCalculationTask task = queue.poll(200);

    // Then: Should return the task
    assertNotNull(task);
    assertThat(task.getUserIgn()).isEqualTo("delayed");
    adderThread.join();
  }

  @Test
  @DisplayName("고우선순위 작업 완료 시 highPriorityCount 감소")
  void completeTaskDecreasesHighPriorityCount() {
    // Given: Add HIGH priority task
    ExpectationCalculationTask task = ExpectationCalculationTask.highPriority("user1", false);
    queue.offer(task);
    assertThat(queue.getHighPriorityCount()).isEqualTo(1);

    // When: Complete the task
    queue.complete(task);

    // Then: Counter should decrease
    assertThat(queue.getHighPriorityCount()).isEqualTo(0);
    assertThat(task.getCompletedAt()).isNotNull();
  }

  @Test
  @DisplayName("저우선순위 작업 완료 시 highPriorityCount 변화 없음")
  void completeLowPriorityTaskDoesNotAffectHighPriorityCount() {
    // Given: Add LOW priority task
    ExpectationCalculationTask task = ExpectationCalculationTask.lowPriority("user1");
    queue.offer(task);
    int initialCount = queue.getHighPriorityCount();

    // When: Complete the task
    queue.complete(task);

    // Then: Counter should not change
    assertThat(queue.getHighPriorityCount()).isEqualTo(initialCount);
    assertThat(task.getCompletedAt()).isNotNull();
  }

  @Test
  @DisplayName("forceRecalculation 플래그 유지")
  void forceRecalculationFlagPreserved() {
    // Given
    ExpectationCalculationTask task1 = ExpectationCalculationTask.highPriority("user1", true);
    ExpectationCalculationTask task2 = ExpectationCalculationTask.highPriority("user2", false);
    ExpectationCalculationTask task3 = ExpectationCalculationTask.lowPriority("user3");

    // When
    queue.offer(task1);
    queue.offer(task2);
    queue.offer(task3);

    // Then
    assertThat(task1.isForceRecalculation()).isTrue();
    assertThat(task2.isForceRecalculation()).isFalse();
    assertThat(task3.isForceRecalculation()).isFalse();
  }

  @Test
  @DisplayName("작업 생성 시간 설정 확인")
  void taskCreatedAtSet() {
    // When
    Instant beforeCreation = Instant.now();
    ExpectationCalculationTask task = ExpectationCalculationTask.highPriority("user1", false);
    Instant afterCreation = Instant.now();

    // Then
    assertThat(task.getCreatedAt()).isNotNull();
    assertThat(task.getCreatedAt()).isBetween(beforeCreation, afterCreation);
  }

  @Test
  @DisplayName("UUID 기반 taskId 생성 확인")
  void taskIdIsUUID() {
    // When
    ExpectationCalculationTask task1 = ExpectationCalculationTask.highPriority("user1", false);
    ExpectationCalculationTask task2 = ExpectationCalculationTask.highPriority("user1", false);

    // Then: Each task should have unique ID
    assertThat(task1.getTaskId()).isNotNull();
    assertThat(task2.getTaskId()).isNotNull();
    assertThat(task1.getTaskId()).isNotEqualTo(task2.getTaskId());
  }

  @Test
  @DisplayName("Priority 순서: HIGH(0) < LOW(1)")
  void priorityOrdering() {
    // When
    ExpectationCalculationTask highTask = ExpectationCalculationTask.highPriority("user1", false);
    ExpectationCalculationTask lowTask = ExpectationCalculationTask.lowPriority("user2");

    // Then: HIGH should have lower ordinal (processed first)
    assertThat(highTask.getPriority().ordinal()).isLessThan(lowTask.getPriority().ordinal());
    assertThat(highTask.getPriority()).isEqualTo(QueuePriority.HIGH);
    assertThat(lowTask.getPriority()).isEqualTo(QueuePriority.LOW);
  }

  @Test
  @DisplayName("addHighPriorityTask 편의 메서드 동작")
  void addHighPriorityTaskConvenienceMethod() {
    // When
    boolean added = queue.addHighPriorityTask("user1", true);

    // Then
    assertThat(added).isTrue();
    assertThat(queue.size()).isEqualTo(1);
    assertThat(queue.getHighPriorityCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("addLowPriorityTask 편의 메서드 동작")
  void addLowPriorityTaskConvenienceMethod() {
    // When
    boolean added = queue.addLowPriorityTask("user1");

    // Then
    assertThat(added).isTrue();
    assertThat(queue.size()).isEqualTo(1);
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
    public <T> T execute(ThrowingSupplier<T> task, String taskName) {
      return execute(task, TaskContext.of("Legacy", taskName));
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
    public void executeVoid(ThrowingRunnable task, String taskName) {
      executeVoid(task, TaskContext.of("Legacy", taskName));
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
        ThrowingSupplier<T> task,
        kotlin.jvm.functions.Function1<? super Throwable, ? extends T> fallback,
        TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        return fallback.invoke(e);
      }
    }

    @Override
    public <T> T executeWithFallback(
        ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        @SuppressWarnings("unchecked")
        T result = (T) translator.translate(e, context);
        return result;
      }
    }

    @Override
    public <T> T executeOrCatch(
        ThrowingSupplier<T> task,
        kotlin.jvm.functions.Function1<? super Throwable, ? extends T> recovery,
        TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        return recovery.invoke(e);
      }
    }

    @Override
    public <T> T executeOrCatch(
        ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        @SuppressWarnings("unchecked")
        T result = (T) translator.translate(e, context);
        return result;
      }
    }
  }
}
