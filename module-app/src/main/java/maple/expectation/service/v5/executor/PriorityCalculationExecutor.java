package maple.expectation.service.v5.executor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.service.v5.queue.PriorityCalculationQueue;
import maple.expectation.service.v5.worker.ExpectationCalculationWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: Priority Executor - Manages calculation worker pool
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Worker pool lifecycle (start/shutdown)
 *   <li>Task submission to priority queue
 *   <li>Graceful shutdown with timeout
 * </ul>
 *
 * <h3>Task Submission Flow</h3>
 *
 * <ol>
 *   <li>Client submits task with priority (HIGH/LOW)
 *   <li>Task added to PriorityCalculationQueue
 *   <li>Worker polls queue and processes
 *   <li>Results persisted to MySQL
 *   <li>Event published to Redis Stream
 * </ol>
 */
@Slf4j
@Component
public class PriorityCalculationExecutor {

  private final PriorityCalculationQueue queue;
  private final ExpectationCalculationWorker worker;
  private final LogicExecutor executor;
  private final int workerPoolSize;
  private final int shutdownTimeoutSeconds;

  private ExecutorService workerPool;
  private volatile boolean running = false;

  public PriorityCalculationExecutor(
      PriorityCalculationQueue queue,
      ExpectationCalculationWorker worker,
      LogicExecutor executor,
      @Value("${app.v5.worker-pool-size:4}") int workerPoolSize,
      @Value("${app.v5.shutdown-timeout-seconds:30}") int shutdownTimeoutSeconds) {
    this.queue = queue;
    this.worker = worker;
    this.executor = executor;
    this.workerPoolSize = workerPoolSize;
    this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
  }

  /** Start worker pool */
  public void start() {
    if (running) {
      log.warn("[V5-Executor] Already running");
      return;
    }

    TaskContext context = TaskContext.of("V5-Executor", "Start");

    executor.executeVoid(
        () -> {
          workerPool = Executors.newFixedThreadPool(workerPoolSize);
          for (int i = 0; i < workerPoolSize; i++) {
            workerPool.submit(worker);
          }
          running = true;
          log.info("[V5-Executor] Started with {} workers", workerPoolSize);
        },
        context);
  }

  /** Stop worker pool gracefully */
  public void stop() {
    if (!running) {
      log.warn("[V5-Executor] Not running");
      return;
    }

    TaskContext context = TaskContext.of("V5-Executor", "Stop");

    executor.executeVoid(
        () -> {
          running = false;
          workerPool.shutdown();
          try {
            if (!workerPool.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
              log.warn("[V5-Executor] Shutdown timeout, forcing termination");
              workerPool.shutdownNow();
            }
            log.info("[V5-Executor] Stopped gracefully");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workerPool.shutdownNow();
            log.warn("[V5-Executor] Shutdown interrupted");
          }
        },
        context);
  }

  /**
   * Submit high priority task (user-initiated request)
   *
   * @param userIgn character IGN
   * @param forceRecalculation force recalculation flag
   * @return task ID if queued, null if rejected
   */
  public String submitHighPriority(String userIgn, boolean forceRecalculation) {
    TaskContext context = TaskContext.of("V5-Executor", "SubmitHigh", userIgn);

    return executor.executeOrDefault(
        () -> {
          boolean added = queue.addHighPriorityTask(userIgn, forceRecalculation);
          if (added) {
            log.info("[V5-Executor] HIGH priority task queued: userIgn={}", userIgn);
            return "queued";
          }
          log.warn("[V5-Executor] HIGH priority task rejected (backpressure): userIgn={}", userIgn);
          return null;
        },
        null,
        context);
  }

  /**
   * Submit low priority task (batch/scheduled update)
   *
   * @param userIgn character IGN
   * @return task ID if queued, null if rejected
   */
  public String submitLowPriority(String userIgn) {
    TaskContext context = TaskContext.of("V5-Executor", "SubmitLow", userIgn);

    return executor.executeOrDefault(
        () -> {
          boolean added = queue.addLowPriorityTask(userIgn);
          if (added) {
            log.debug("[V5-Executor] LOW priority task queued: userIgn={}", userIgn);
            return "queued";
          }
          log.debug("[V5-Executor] LOW priority task rejected (backpressure): userIgn={}", userIgn);
          return null;
        },
        null,
        context);
  }

  /** Get current queue size */
  public int getQueueSize() {
    return queue.size();
  }

  /** Get high priority task count */
  public int getHighPriorityCount() {
    return queue.getHighPriorityCount();
  }

  /** Check if executor is running */
  public boolean isRunning() {
    return running;
  }
}
