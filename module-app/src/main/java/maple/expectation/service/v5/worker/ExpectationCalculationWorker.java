package maple.expectation.service.v5.worker;

import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.service.v4.EquipmentExpectationServiceV4;
import maple.expectation.service.v5.event.MongoSyncEventPublisherInterface;
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
  private final LogicExecutor executor;
  private final Counter processedCounter;
  private final Counter errorCounter;

  // Optional query side component
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private MongoSyncEventPublisherInterface eventPublisher;

  public ExpectationCalculationWorker(
      PriorityCalculationQueue queue,
      EquipmentExpectationServiceV4 expectationService,
      LogicExecutor executor,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.queue = queue;
    this.expectationService = expectationService;
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

  /**
   * Process next task from queue with full V4 calculation pipeline
   *
   * <h3>Calculation Flow (V4 Service Reuse)</h3>
   *
   * <ol>
   *   <li>GameCharacterFacade.findCharacterByUserIgn() - DB 조회 + 기본 정보 보강
   *   <li>EquipmentDataProvider.getRawEquipmentData() - 장비 데이터 로드 (DB/API)
   *   <li>EquipmentStreamingParser.decompressIfNeeded() - GZIP 해제
   *   <li>EquipmentStreamingParser.parseCubeInputsForPreset() - 프리셋별 파싱 (1,2,3)
   *   <li>PresetCalculationHelper.calculatePreset() - 3개 프리셋 병렬 계산
   *   <li>findMaxPreset() - 최대 기대값 프리셋 선택
   *   <li>ExpectationPersistenceService.saveResults() - 결과 저장 (MySQL)
   *   <li>MongoSyncEventPublisher.publishCalculationCompleted() - 이벤트 발행
   * </ol>
   */
  void processNextTask() {
    ExpectationCalculationTask task = null;
    try {
      task = queue.poll();
      if (task == null) {
        return;
      }

      TaskContext context = TaskContext.of("V5-Worker", "Calculate", task.getUserIgn());

      task.setStartedAt(java.time.Instant.now());

      try {
        executeCalculation(task, context);
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
      if (task != null) {
        queue.complete(task);
      }
    }
  }

  /**
   * Execute full V4 calculation pipeline via EquipmentExpectationServiceV4
   *
   * <p>This method reuses the complete V4 calculation logic which already implements:
   *
   * <ul>
   *   <li>Character lookup via GameCharacterFacade
   *   <li>Equipment data loading via EquipmentDataProvider
   *   <li>Decompression via EquipmentStreamingParser
   *   <li>Preset parsing (1,2,3) via EquipmentStreamingParser
   *   <li>Parallel preset calculation via PresetCalculationHelper
   *   <li>Max preset selection via findMaxPreset
   *   <li>Result persistence via ExpectationPersistenceService
   * </ul>
   *
   * @param task calculation task with userIgn and forceRecalculation flag
   * @param context execution context for logging and metrics
   */
  private void executeCalculation(ExpectationCalculationTask task, TaskContext context) {
    executor.executeVoid(
        () -> {
          EquipmentExpectationResponseV4 response =
              expectationService.calculateExpectation(
                  task.getUserIgn(), task.isForceRecalculation());

          // Publish event to query side if enabled
          if (eventPublisher != null) {
            eventPublisher.publishCalculationCompleted(task.getTaskId(), response);
          }

          log.info(
              "[V5-Worker] Calculation completed: userIgn={}, taskId={}, cost={}, maxPreset={}",
              task.getUserIgn(),
              task.getTaskId(),
              response.getTotalExpectedCost(),
              response.getMaxPresetNo());
        },
        context);
  }
}
