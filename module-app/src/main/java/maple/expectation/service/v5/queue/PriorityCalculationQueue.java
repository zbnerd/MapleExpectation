package maple.expectation.service.v5.queue;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: Priority Queue for Expectation Calculation
 *
 * <h3>Priority Strategy</h3>
 *
 * <ul>
 *   <li>HIGH: User-initiated requests (immediate processing)
 *   <li>LOW: Batch/scheduled updates (background processing)
 * </ul>
 *
 * <h3>Backpressure</h3>
 *
 * <p>Max queue size prevents memory exhaustion. When full, LOW priority tasks are rejected.
 */
@Slf4j
@Component
public class PriorityCalculationQueue {

  private static final int MAX_QUEUE_SIZE = 10_000;
  private static final int HIGH_PRIORITY_CAPACITY = 1_000;

  private final PriorityBlockingQueue<ExpectationCalculationTask> queue;
  private final AtomicInteger highPriorityCount = new AtomicInteger(0);
  private final LogicExecutor executor;

  public PriorityCalculationQueue(LogicExecutor executor) {
    this.executor = executor;
    // FIXED: Priority ordering - HIGH (ordinal=0) processed before LOW (ordinal=1)
    // PriorityBlockingQueue is a min-heap, so smaller ordinal values are processed first
    // No .reversed() needed - would cause priority inversion
    this.queue =
        new PriorityBlockingQueue<>(
            MAX_QUEUE_SIZE,
            Comparator.comparingInt(
                    (ExpectationCalculationTask task) -> task.getPriority().ordinal())
                .thenComparing(ExpectationCalculationTask::getCreatedAt));
  }

  /**
   * Offer task to queue with backpressure control
   *
   * @return true if queued, false if rejected (backpressure)
   */
  public boolean offer(ExpectationCalculationTask task) {
    TaskContext context = TaskContext.of("Queue", "Offer", task.getUserIgn());

    return executor.executeOrDefault(
        () -> {
          if (task.getPriority() == QueuePriority.HIGH) {
            // FIXED: Atomic check-and-increment to prevent race condition
            // Use compareAndSet loop to ensure highPriorityCount never exceeds capacity
            int current;
            do {
              current = highPriorityCount.get();
              if (current >= HIGH_PRIORITY_CAPACITY) {
                log.warn("[Queue] High priority queue full: {}, rejecting", task.getUserIgn());
                return false;
              }
            } while (!highPriorityCount.compareAndSet(current, current + 1));
          }

          boolean added = queue.offer(task);
          if (!added && task.getPriority() == QueuePriority.HIGH) {
            // Rollback counter if queue rejected the task
            highPriorityCount.decrementAndGet();
            log.warn("[Queue] Queue full, rejecting: {}", task.getUserIgn());
          }
          return added;
        },
        false,
        context);
  }

  /**
   * Add HIGH priority task (user-initiated request)
   *
   * @return true if queued, false if rejected (backpressure)
   */
  public boolean addHighPriorityTask(String userIgn, boolean forceRecalculation) {
    return offer(ExpectationCalculationTask.highPriority(userIgn, forceRecalculation));
  }

  /**
   * Add LOW priority task (batch/scheduled update)
   *
   * @return true if queued, false if rejected (backpressure)
   */
  public boolean addLowPriorityTask(String userIgn) {
    return offer(ExpectationCalculationTask.lowPriority(userIgn));
  }

  /** Poll next task (blocking with timeout) */
  public ExpectationCalculationTask poll() throws InterruptedException {
    return queue.take();
  }

  /**
   * Poll next task with timeout (non-blocking when timeout expires)
   *
   * @param timeoutMs timeout in milliseconds
   * @return task or null if timeout
   */
  public ExpectationCalculationTask poll(long timeoutMs) {
    try {
      return queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /** Get current queue size */
  public int size() {
    return queue.size();
  }

  /** Get high priority task count */
  public int getHighPriorityCount() {
    return highPriorityCount.get();
  }

  /** Mark task as completed and decrement counters */
  public void complete(ExpectationCalculationTask task) {
    if (task.getPriority() == QueuePriority.HIGH) {
      highPriorityCount.decrementAndGet();
    }
    task.setCompletedAt(java.time.Instant.now());
  }
}
