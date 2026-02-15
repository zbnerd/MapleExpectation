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
 * V5 CQRS: Priority Executor - Manages calculation worker pool with Fast Lane isolation
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Worker pool lifecycle (start/shutdown)
 *   <li>Task submission to priority queue
 *   <li>Graceful shutdown with timeout
 * </ul>
 *
 * <h3>P1 FIX: Thread Pool Isolation</h3>
 *
 * <p>Implemented separate pools for HIGH and LOW priority tasks to prevent batch jobs from starving
 * user requests:
 *
 * <ul>
 *   <li><b>High Priority Pool (Fast Lane)</b>: Dedicated pool for user-initiated requests (50% of
 *       workers)
 *   <li><b>Low Priority Pool (Background)</b>: Shared pool for batch/scheduled updates (50% of
 *       workers)
 * </ul>
 *
 * <h3>Task Submission Flow</h3>
 *
 * <ol>
 *   <li>Client submits task with priority (HIGH/LOW)
 *   <li>Task added to PriorityCalculationQueue
 *   <li>Workers from appropriate pool poll queue and process
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
  private final int highPriorityWorkerRatio; // P1 FIX: Ratio for HIGH priority pool

  // P1 FIX: Separate pools for HIGH and LOW priority to prevent starvation
  private ExecutorService highPriorityPool; // Fast Lane for user requests
  private ExecutorService lowPriorityPool; // Background pool for batch jobs
  private volatile boolean running = false;

  public PriorityCalculationExecutor(
      PriorityCalculationQueue queue,
      ExpectationCalculationWorker worker,
      LogicExecutor executor,
      @Value("${app.v5.worker-pool-size:4}") int workerPoolSize,
      @Value("${app.v5.shutdown-timeout-seconds:30}") int shutdownTimeoutSeconds,
      @Value("${app.v5.high-priority-worker-ratio:0.5}") double highPriorityWorkerRatio) {
    this.queue = queue;
    this.worker = worker;
    this.executor = executor;
    this.workerPoolSize = workerPoolSize;
    this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    this.highPriorityWorkerRatio = (int) (highPriorityWorkerRatio * 100); // 50% by default
  }

  /**
   * Start worker pools with thread isolation
   *
   * <p>P1 FIX: Separate pools prevent batch jobs from occupying all worker threads, ensuring user
   * requests always have dedicated capacity.
   */
  public void start() {
    if (running) {
      log.warn("[V5-Executor] Already running");
      return;
    }

    TaskContext context = TaskContext.of("V5-Executor", "Start");

    executor.executeVoid(
        () -> {
          // Calculate pool sizes (ensure at least 1 worker per pool)
          int highPriorityCount =
              Math.max(1, (int) Math.ceil(workerPoolSize * highPriorityWorkerRatio / 100.0));
          int lowPriorityCount = Math.max(1, workerPoolSize - highPriorityCount);

          // P1 FIX: Create separate pools for isolation
          highPriorityPool = Executors.newFixedThreadPool(highPriorityCount);
          lowPriorityPool = Executors.newFixedThreadPool(lowPriorityCount);

          // Submit workers to HIGH priority pool (Fast Lane)
          for (int i = 0; i < highPriorityCount; i++) {
            highPriorityPool.submit(worker);
          }

          // Submit workers to LOW priority pool (Background)
          for (int i = 0; i < lowPriorityCount; i++) {
            lowPriorityPool.submit(worker);
          }

          running = true;
          log.info(
              "[V5-Executor] Started with {} total workers (HIGH: {}, LOW: {})",
              workerPoolSize,
              highPriorityCount,
              lowPriorityCount);
        },
        context);
  }

  /** Stop worker pools gracefully */
  public void stop() {
    if (!running) {
      log.warn("[V5-Executor] Not running");
      return;
    }

    TaskContext context = TaskContext.of("V5-Executor", "Stop");

    executor.executeVoid(
        () -> {
          running = false;
          highPriorityPool.shutdown();
          lowPriorityPool.shutdown();

          try {
            if (!awaitTermination(highPriorityPool, lowPriorityPool)) {
              log.warn("[V5-Executor] Shutdown timeout, forcing termination");
              highPriorityPool.shutdownNow();
              lowPriorityPool.shutdownNow();
            }
            log.info("[V5-Executor] Stopped gracefully");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            highPriorityPool.shutdownNow();
            lowPriorityPool.shutdownNow();
            log.warn("[V5-Executor] Shutdown interrupted");
          }
        },
        context);
  }

  /** Wait for both pools to terminate with timeout */
  private boolean awaitTermination(ExecutorService pool1, ExecutorService pool2)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + shutdownTimeoutSeconds * 1000L;

    // Wait for pool1
    long remaining1 = Math.max(0, deadline - System.currentTimeMillis());
    boolean terminated1 = pool1.awaitTermination(remaining1, TimeUnit.MILLISECONDS);

    // Wait for pool2
    long remaining2 = Math.max(0, deadline - System.currentTimeMillis());
    boolean terminated2 = pool2.awaitTermination(remaining2, TimeUnit.MILLISECONDS);

    return terminated1 && terminated2;
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
