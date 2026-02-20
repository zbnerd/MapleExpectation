package maple.expectation.service.v5.worker;

import io.micrometer.core.instrument.Counter;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.service.v4.EquipmentExpectationServiceV4;
import maple.expectation.service.v5.event.MongoSyncEventPublisherInterface;
import maple.expectation.service.v5.queue.ExpectationCalculationTask;
import maple.expectation.service.v5.queue.PriorityCalculationQueue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: Calculation Worker - Processes queue tasks
 *
 * <h3>Flow</h3>
 *
 * <ol>
 *   <li>Poll task from PriorityCalculationQueue
 *   <li>Calculate using V4 service (reuse existing logic)
 *   <li>Publish event to Redis Stream
 *   <li>Upsert to MongoDB view
 *   <li>Complete task in queue
 * </ol>
 *
 * <h3>Section 12 Compliance</h3>
 *
 * <p>All exception handling delegated to LogicExecutor/CheckedLogicExecutor.
 */
@Slf4j
@Component
public class ExpectationCalculationWorker implements Runnable {

  // ADR-080 Fix 2: Track active workers for startup verification
  private static final AtomicInteger ACTIVE_WORKERS = new AtomicInteger(0);

  /**
   * Get the current number of active workers (for monitoring/verification).
   *
   * @return active worker count
   */
  public static int getActiveWorkerCount() {
    return ACTIVE_WORKERS.get();
  }

  private final PriorityCalculationQueue queue;
  private final EquipmentExpectationServiceV4 expectationService;
  private final LogicExecutor executor;
  private final CheckedLogicExecutor checkedExecutor;
  private final Counter processedCounter;
  private final Counter errorCounter;

  // Optional query side component
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private MongoSyncEventPublisherInterface eventPublisher;

  public ExpectationCalculationWorker(
      PriorityCalculationQueue queue,
      EquipmentExpectationServiceV4 expectationService,
      LogicExecutor executor,
      @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedExecutor,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.queue = queue;
    this.expectationService = expectationService;
    this.executor = executor;
    this.checkedExecutor = checkedExecutor;
    this.processedCounter = meterRegistry.counter("calculation.worker.processed");
    this.errorCounter = meterRegistry.counter("calculation.worker.errors");
  }

  @Override
  public void run() {
    // ADR-080 Fix 2: Track active worker count
    ACTIVE_WORKERS.incrementAndGet();
    log.info("[V5-Worker] Calculation worker started (active: {})", ACTIVE_WORKERS.get());

    while (!Thread.currentThread().isInterrupted()) {
      processNextTaskWithRecovery();
    }

    log.info("[V5-Worker] Calculation worker stopped");
  }

  /**
   * Process next task with recovery pattern (Section 12 compliant).
   *
   * <p>Uses CheckedLogicExecutor for queue.poll() which throws InterruptedException.
   */
  private void processNextTaskWithRecovery() {
    ExpectationCalculationTask task = pollTaskOrNull();

    if (task == null) {
      return;
    }

    TaskContext context = TaskContext.of("V5-Worker", "Calculate", task.getUserIgn());
    task.setStartedAt(Instant.now());

    executeCalculationWithFinally(task, context);
  }

  /** Poll task from queue, returning null on interruption (graceful shutdown). */
  private ExpectationCalculationTask pollTaskOrNull() {
    return executor.executeOrDefault(
        () -> {
          try {
            return queue.poll();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[V5-Worker] Worker interrupted, shutting down");
            throw new WorkerShutdownException(e);
          }
        },
        null,
        TaskContext.of("V5-Worker", "PollTask"));
  }

  /** Execute calculation with finally pattern ensuring task completion. */
  private void executeCalculationWithFinally(ExpectationCalculationTask task, TaskContext context) {
    executor.executeWithFinally(
        () -> {
          executeCalculation(task);
          return null; // Return null since executeWithFinally requires a return value
        },
        () -> queue.complete(task),
        context);
  }

  /** Execute calculation with error recovery. */
  private void executeCalculation(ExpectationCalculationTask task) {
    executor.executeOrCatch(
        () -> {
          EquipmentExpectationResponseV4 response =
              expectationService.calculateExpectation(
                  task.getUserIgn(), task.isForceRecalculation());

          // Publish event to query side if enabled
          if (eventPublisher != null) {
            eventPublisher.publishCalculationCompleted(task.getTaskId(), response);
          }

          processedCounter.increment();
          log.info(
              "[V5-Worker] Calculation completed: userIgn={}, taskId={}, cost={}, maxPreset={}",
              task.getUserIgn(),
              task.getTaskId(),
              response.getTotalExpectedCost(),
              response.getMaxPresetNo());
          return null;
        },
        e -> {
          errorCounter.increment();
          log.error("[V5-Worker] Calculation failed for: {}", task.getUserIgn(), e);
          return null;
        },
        TaskContext.of("V5-Worker", "ExecuteCalculation", task.getUserIgn()));
  }

  /** RuntimeException to signal graceful worker shutdown. */
  private static class WorkerShutdownException extends RuntimeException {
    WorkerShutdownException(InterruptedException cause) {
      super(cause);
    }
  }
}
