package maple.expectation.scheduler;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.DistributedLockException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.lock.LockStrategy;
import maple.expectation.infrastructure.persistence.repository.EquipmentExpectationSummaryRepository;
import maple.expectation.service.v4.buffer.ExpectationWriteBackBuffer;
import maple.expectation.service.v4.buffer.ExpectationWriteTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expectation 배치 DB 동기화 스케줄러 (#266)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): LikeSyncScheduler 패턴 재사용
 *   <li>Red (SRE): 분산 락으로 중복 실행 방지
 *   <li>Green (Performance): 5초 주기 배치 동기화
 * </ul>
 *
 * <h3>동작 방식</h3>
 *
 * <ol>
 *   <li>5초마다 버퍼에서 최대 100개 작업 추출
 *   <li>분산 락 획득 후 배치 upsert
 *   <li>락 획득 실패 시 다음 주기로 연기
 * </ol>
 *
 * <h3>메트릭</h3>
 *
 * <ul>
 *   <li>expectation.buffer.flushed: 플러시된 작업 수
 * </ul>
 *
 * <h3>Issue #283 P1-10: Scale-out 분산 안전성 분석</h3>
 *
 * <p>이 스케줄러는 자체적으로 volatile/in-memory shutdown 플래그를 보유하지 않습니다. Shutdown 상태는 {@link
 * ExpectationWriteBackBuffer#isShuttingDown()}에 위임하며, 분산 락({@code expectation-batch-sync-lock})으로
 * 다중 인스턴스 간 중복 실행을 방지합니다.
 *
 * <ul>
 *   <li>분산 락: {@link LockStrategy}를 통한 Redis 기반 분산 락 사용 -> Scale-out 안전
 *   <li>Shutdown 플래그: 버퍼에 위임 -> 각 인스턴스가 독립적으로 자신의 shutdown 관리
 *   <li>@Scheduled: 각 인스턴스에서 독립 실행되나, 분산 락이 한 번에 하나만 flush 허용
 * </ul>
 *
 * <p><b>결론: Redis 분산 락 기반으로 이미 Scale-out 안전. 추가 변환 불필요.</b>
 *
 * @see ExpectationWriteBackBuffer 메모리 버퍼
 * @see LikeSyncScheduler 참조 패턴
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "scheduler.expectation-sync.enabled",
    havingValue = "true",
    matchIfMissing = true // 기본 활성화
    )
public class ExpectationBatchWriteScheduler {

  private final ExpectationWriteBackBuffer buffer;
  private final EquipmentExpectationSummaryRepository repository;
  private final LockStrategy lockStrategy;
  private final LogicExecutor executor;
  private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
  private final maple.expectation.config.BatchProperties batchProperties;

  /** 분산 락 이름 */
  private static final String LOCK_NAME = "expectation-batch-sync-lock";

  /**
   * 배치 DB 동기화 (5초마다)
   *
   * <h4>CLAUDE.md Section 12 준수</h4>
   *
   * <p>try-catch 금지 → LogicExecutor.executeOrCatch() 사용
   *
   * <h4>P0: Red Agent 스케줄러 양보</h4>
   *
   * <p>Shutdown 중이면 스케줄러가 양보하여 ShutdownHandler에게 버퍼 처리를 위임합니다. 이렇게 함으로써 스케줄러와 ShutdownHandler 간의
   * 경합을 방지합니다.
   */
  @Scheduled(fixedDelay = 5000)
  public void flush() {
    // Red Agent: Shutdown 중이면 스케줄러는 양보
    if (buffer.isShuttingDown()) {
      log.debug("[ExpectationBatch] Skipping flush during shutdown");
      return;
    }

    TaskContext context = TaskContext.of("Scheduler", "ExpectationBatch.Flush");

    executor.executeOrCatch(
        () -> {
          // 버퍼가 비어있으면 스킵
          if (buffer.isEmpty()) {
            return null;
          }

          // 분산 락으로 중복 실행 방지
          lockStrategy.executeWithLock(
              LOCK_NAME,
              0,
              10,
              () -> {
                flushBatch();
                return null;
              });

          return null;
        },
        e -> {
          handleFlushFailure(e);
          return null;
        },
        context);
  }

  /** 배치 플러시 실행 */
  private void flushBatch() {
    List<ExpectationWriteTask> batch = buffer.drain(batchProperties.expectationWriteSize());

    if (batch.isEmpty()) {
      return;
    }

    // 개별 upsert (batch upsert가 구현될 때까지)
    int successCount = 0;
    for (ExpectationWriteTask task : batch) {
      executor.executeOrCatch(
          () -> {
            repository.upsertExpectationSummary(
                task.characterId(),
                task.presetNo(),
                task.totalExpectedCost(),
                task.blackCubeCost(),
                task.redCubeCost(),
                task.additionalCubeCost(),
                task.starforceCost());
            return null;
          },
          e -> {
            log.error("[ExpectationBatch] Upsert failed for {}: {}", task.key(), e.getMessage());
            return null;
          },
          TaskContext.of("ExpectationBatch", "Upsert", task.key()));
      successCount++;
    }

    // 메트릭 기록
    meterRegistry.counter("expectation.buffer.flushed").increment(successCount);
    log.debug(
        "[ExpectationBatch] Flushed {} tasks, remaining={}",
        successCount,
        buffer.getPendingCount());
  }

  /** 플러시 실패 처리 */
  private void handleFlushFailure(Throwable t) {
    if (t instanceof DistributedLockException) {
      log.debug("[ExpectationBatch] Lock acquisition skipped: another server is processing");
      return;
    }
    log.error("[ExpectationBatch] Flush failed: {}", t.getMessage());
  }
}
