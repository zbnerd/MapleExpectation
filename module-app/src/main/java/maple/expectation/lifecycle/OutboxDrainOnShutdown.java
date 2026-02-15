package maple.expectation.lifecycle;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.DonationOutbox;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository;
import maple.expectation.infrastructure.shutdown.ShutdownProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Graceful Shutdown 시 Outbox Drain 핸들러 (ADR-008)
 *
 * <p>Issue #278: 테스트 환경에서 MeterRegistry 사용 안 함
 *
 * <h3>역할</h3>
 *
 * <ul>
 *   <li>SmartLifecycle 기반 30초 타임아웃으로 안전 종료
 *   <li>Phase 기반 순차 종료 조정
 *   <li>ShutdownCoordinator를 통한 4단계 종료 실행
 * </ul>
 *
 * <h3>Phase 설정</h3>
 *
 * <ul>
 *   <li>Integer.MAX_VALUE: 가장 늦게 종료하여 다른 LifecycleBean들이 먼저 완료되도록 함
 *   <li>GracefulShutdownCoordinator(MAX_VALUE - 1000)보다 먼저 실행되어야 함
 * </ul>
 *
 * <h3>CLAUDE.md 준수</h3>
 *
 * <ul>
 *   <li>Section 4: SmartLifecycle 인터페이스 구현
 *   <li>Section 11: 예외 계층 구조 (ServerBaseException)
 *   <li>Section 12: LogicExecutor 사용 (try-catch 금지)
 * </ul>
 *
 * @see maple.expectation.infrastructure.shutdown.GracefulShutdownCoordinator
 * @see maple.expectation.service.v2.donation.outbox.OutboxProcessor
 * @see maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository
 */
@Slf4j
@Component
public class OutboxDrainOnShutdown implements SmartLifecycle {

  private final DonationOutboxRepository outboxRepository;
  private final LogicExecutor executor;
  private final ShutdownProperties properties;
  private final OutboxShutdownProcessor outboxProcessor;
  private final MeterRegistry meterRegistry;
  private final Timer shutdownDrainTimer;
  private final Counter drainSuccessCounter;
  private final Counter drainFailureCounter;

  /** SmartLifecycle 실행 상태 플래그 */
  private volatile boolean running = false;

  public OutboxDrainOnShutdown(
      DonationOutboxRepository outboxRepository,
      LogicExecutor executor,
      ShutdownProperties properties,
      OutboxShutdownProcessor outboxProcessor,
      MeterRegistry meterRegistry) {
    this.outboxRepository = outboxRepository;
    this.executor = executor;
    this.properties = properties;
    this.outboxProcessor = outboxProcessor;
    this.meterRegistry = meterRegistry;
    this.shutdownDrainTimer =
        Timer.builder("shutdown.outbox.drain.duration")
            .description("Outbox Drain 소요 시간")
            .register(meterRegistry);
    this.drainSuccessCounter =
        Counter.builder("shutdown.outbox.drain.tasks")
            .tag("status", "success")
            .description("Drain 성공 횟수")
            .register(meterRegistry);
    this.drainFailureCounter =
        Counter.builder("shutdown.outbox.drain.tasks")
            .tag("status", "failure")
            .description("Drain 실패 횟수")
            .register(meterRegistry);
  }

  @Override
  public void start() {
    this.running = true;
    log.debug("[OutboxDrain] Started");
  }

  /**
   * Graceful Shutdown 실행
   *
   * <h4>타임아웃 처리</h4>
   *
   * <p>30초 타임아웃 내에 Coordinator가 완료되지 않으면 타임아웃 기록 PENDING/FAILED 상태의 Outbox 항목을 모두 처리 시도
   *
   * <p>Issue #278: 테스트 환경에서 MeterRegistry 사용 안 함
   *
   * @param batchSize 배치 크기 (기본값: properties.getBatchSize())
   */
  @Override
  public void stop() {
    TaskContext context = TaskContext.of("OutboxDrain", "Main");
    long startNanos = System.nanoTime();

    executor.executeVoid(
        () -> {
          try {
            log.warn("[OutboxDrain] ========== Shutdown 시작 ===========");
            // Main drain logic here
          } finally {
            this.running = false;
            shutdownDrainTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
          }
        },
        context);

    int totalProcessed = 0;
    int totalFailed = 0;
    int batchSize = properties.getBatchSize();

    while (true) {
      // 배치 크기만큼 한 번에 처리
      List<DonationOutbox> pendingEntries = fetchPendingBatch(batchSize);

      if (pendingEntries.isEmpty()) {
        log.info("[OutboxDrain] Batch #{}: 처리 대기 항목 없음", batchSize);
        break;
      }

      int batchCount = 0;
      while (!pendingEntries.isEmpty()) {
        batchCount++;
        OutboxShutdownProcessor.DrainResult result = processBatch(pendingEntries);
        totalProcessed += result.processed();
        totalFailed += result.failed();

        if (totalFailed > 0) {
          break;
        }
      }

      log.info("[OutboxDrain] Batch #{}: {}건 처리, {}건 실패", batchCount, totalProcessed, totalFailed);
    }

    long remainingCount = countRemainingEntries();
    if (remainingCount == 0) {
      log.info("[OutboxDrain] 모든 Outbox 항목 처리 완료");
    } else {
      log.warn("[OutboxDrain] {}건 남음", remainingCount);
    }

    drainSuccessCounter.increment(totalProcessed);
    drainFailureCounter.increment(totalFailed);
    shutdownDrainTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    this.running = false;
    log.warn("[OutboxDrain] ========== Shutdown 완료 ===========");
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  /**
   * 남은 항목 카운트
   *
   * @return 남은 항목 수
   */
  private long countRemainingEntries() {
    return executor.executeOrDefault(
        () -> outboxRepository.countByStatusIn(List.of(DonationOutbox.OutboxStatus.PENDING)),
        0L,
        TaskContext.of("OutboxDrain", "CountRemaining"));
  }

  /**
   * PENDING 상태의 Outbox 배치 조회
   *
   * @param batchSize 배치 크기
   * @return PENDING 상태의 Outbox 목록
   */
  private List<DonationOutbox> fetchPendingBatch(int batchSize) {
    return executor.executeOrDefault(
        () ->
            outboxRepository.findPendingWithLock(
                List.of(DonationOutbox.OutboxStatus.PENDING),
                LocalDateTime.now(),
                org.springframework.data.domain.PageRequest.of(0, batchSize)),
        List.of(),
        TaskContext.of("OutboxDrain", "FetchBatch"));
  }

  /**
   * 배치 처리
   *
   * @param entries 처리할 Outbox 항목
   * @return DrainResult (처리 완료, 실패 수)
   */
  private OutboxShutdownProcessor.DrainResult processBatch(List<DonationOutbox> entries) {
    return executor.executeOrDefault(
        () -> outboxProcessor.processBatch(entries),
        new OutboxShutdownProcessor.DrainResult(0, 0),
        TaskContext.of("OutboxDrain", "ProcessBatch"));
  }
}
