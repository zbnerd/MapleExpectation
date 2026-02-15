package maple.expectation.service.v5.worker;

import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.service.v4.EquipmentExpectationServiceV4;
import maple.expectation.service.v5.event.MongoSyncEventPublisher;
import maple.expectation.service.v5.queue.ExpectationCalculationTask;
import maple.expectation.service.v5.queue.PriorityCalculationQueue;
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
 */
@Slf4j
@Component
public class ExpectationCalculationWorker implements Runnable {

  private final PriorityCalculationQueue queue;
  private final EquipmentExpectationServiceV4 expectationService;
  private final MongoSyncEventPublisher eventPublisher;
  private final LogicExecutor executor;
  private final Counter processedCounter;
  private final Counter errorCounter;

  public ExpectationCalculationWorker(
      PriorityCalculationQueue queue,
      EquipmentExpectationServiceV4 expectationService,
      MongoSyncEventPublisher eventPublisher,
      LogicExecutor executor,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.queue = queue;
    this.expectationService = expectationService;
    this.eventPublisher = eventPublisher;
    this.executor = executor;
    this.processedCounter = meterRegistry.counter("calculation.worker.processed");
    this.errorCounter = meterRegistry.counter("calculation.worker.errors");
  }

  @Override
  public void run() {
    log.info("[V5-Worker] Calculation worker started");

    while (!Thread.currentThread().isInterrupted()) {
      executor.executeVoid(this::processNextTask, TaskContext.of("V5-Worker", "ProcessTask"));
    }

    log.info("[V5-Worker] Calculation worker stopped");
  }

  void processNextTask() {
    try {
      ExpectationCalculationTask task = queue.poll();
      if (task == null) {
        return;
      }

      TaskContext context = TaskContext.of("V5-Worker", "Calculate", task.getUserIgn());

      task.setStartedAt(java.time.Instant.now());

      try {
        EquipmentExpectationResponseV4 response =
            expectationService.calculateExpectation(task.getUserIgn(), task.isForceRecalculation());

        eventPublisher.publishCalculationCompleted(task.getTaskId(), response);

        processedCounter.increment();

      } catch (Exception e) {
        errorCounter.increment();
        log.error("[V5-Worker] Calculation failed for: {}", task.getUserIgn(), e);
      } finally {
        queue.complete(task);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.info("[V5-Worker] Worker interrupted, shutting down");
      return;
    } catch (Exception e) {
      log.error("[V5-Worker] Unexpected error in processNextTask", e);
    }
  }
}
