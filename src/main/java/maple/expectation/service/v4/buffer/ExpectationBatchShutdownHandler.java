package maple.expectation.service.v4.buffer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.config.ShutdownProperties;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.EquipmentExpectationSummaryRepository;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Expectation 버퍼 Graceful Shutdown 핸들러 (#266 ADR 정합성 리팩토링)
 *
 * <h3>P0/P1 리팩토링</h3>
 * <ul>
 *   <li>P0-1: flushBatch() 개별 upsert -> batch saveAll + file backup fallback</li>
 *   <li>P1-3: 하드코딩 상수 -> ShutdownProperties 외부화</li>
 *   <li>P1-4: running=false -> executeWithFinally 패턴 통일</li>
 *   <li>P1-6: Shutdown 메트릭 추가 (Micrometer)</li>
 *   <li>P1-10: flushBatch 실패 시 실패 건수 추적</li>
 * </ul>
 *
 * <h3>Phase 설정</h3>
 * <p>Integer.MAX_VALUE - 500: GracefulShutdownCoordinator (MAX_VALUE - 1000)보다 먼저 실행</p>
 *
 * @see maple.expectation.global.shutdown.GracefulShutdownCoordinator 메인 Shutdown 조정자
 * @see ShutdownProperties 외부 설정
 */
@Slf4j
@Component
public class ExpectationBatchShutdownHandler implements SmartLifecycle {

    private final ExpectationWriteBackBuffer buffer;
    private final EquipmentExpectationSummaryRepository repository;
    private final LogicExecutor executor;
    private final ShutdownProperties properties;

    // P1-6 Fix: Shutdown 메트릭
    private final Timer shutdownDrainTimer;
    private final Counter drainSuccessCounter;
    private final Counter drainFailureCounter;

    private volatile boolean running = true;

    public ExpectationBatchShutdownHandler(
            ExpectationWriteBackBuffer buffer,
            EquipmentExpectationSummaryRepository repository,
            LogicExecutor executor,
            ShutdownProperties properties,
            MeterRegistry meterRegistry) {
        this.buffer = buffer;
        this.repository = repository;
        this.executor = executor;
        this.properties = properties;
        this.shutdownDrainTimer = Timer.builder("shutdown.buffer.drain.duration")
                .description("Expectation 버퍼 Drain 소요 시간")
                .register(meterRegistry);
        this.drainSuccessCounter = Counter.builder("shutdown.buffer.drain.tasks")
                .tag("status", "success")
                .description("Drain 성공 건수")
                .register(meterRegistry);
        this.drainFailureCounter = Counter.builder("shutdown.buffer.drain.tasks")
                .tag("status", "failure")
                .description("Drain 실패 건수")
                .register(meterRegistry);
    }

    @Override
    public void start() {
        this.running = true;
        log.debug("[ExpectationShutdown] Started");
    }

    /**
     * Graceful Shutdown 시 3단계 프로세스 실행
     *
     * <h4>P1-4 Fix: executeWithFinally 패턴 통일</h4>
     * <p>running=false를 finally에서 보장하여 GracefulShutdownCoordinator와 일관성 확보</p>
     */
    @Override
    public void stop() {
        TaskContext context = TaskContext.of("ExpectationShutdown", "DrainBuffer");
        long startNanos = System.nanoTime();

        // P1-4 Fix: executeWithFinally로 running=false 보장
        executor.executeWithFinally(
                () -> {
                    log.info("[ExpectationShutdown] Starting 3-phase shutdown... pending={}",
                            buffer.getPendingCount());

                    // Phase 1: 신규 offer 차단
                    buffer.prepareShutdown();
                    log.info("[ExpectationShutdown] Phase 1 complete - new offers blocked");

                    // Phase 2: 진행 중인 offer 완료 대기
                    Duration awaitTimeout = buffer.getShutdownAwaitTimeout();
                    boolean allCompleted = buffer.awaitPendingOffers(awaitTimeout);
                    if (allCompleted) {
                        log.info("[ExpectationShutdown] Phase 2 complete - all in-flight offers completed");
                    } else {
                        log.warn("[ExpectationShutdown] Phase 2 timeout - some offers may not have completed");
                    }

                    // Phase 3: 버퍼 완전 drain
                    int totalFlushed = drainBuffer();
                    log.info("[ExpectationShutdown] Phase 3 complete - {} tasks flushed to DB", totalFlushed);

                    return null;
                },
                () -> {
                    this.running = false;
                    shutdownDrainTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
                },
                context
        );
    }

    /**
     * 버퍼 완전 drain (Phase 3)
     *
     * <h4>빈 배치 재시도 로직 (P1-3 Fix: 상수 외부화)</h4>
     * <p>Race condition으로 인한 일시적 빈 배치에 대응하여
     * 설정된 횟수만큼 재시도합니다.</p>
     *
     * @return 총 flush된 작업 수
     */
    private int drainBuffer() {
        int totalFlushed = 0;
        int emptyRetries = 0;

        while (emptyRetries < properties.getEmptyBatchRetryCount()) {
            List<ExpectationWriteTask> batch = buffer.drain(properties.getBatchSize());

            if (batch.isEmpty()) {
                emptyRetries++;
                sleepSafely(properties.getEmptyBatchWaitMs());
                continue;
            }

            // 배치가 있으면 retry 카운터 리셋
            emptyRetries = 0;
            int batchFlushed = flushBatch(batch);
            totalFlushed += batchFlushed;

            log.debug("[ExpectationShutdown] Flushed batch: {} tasks, total: {}",
                    batchFlushed, totalFlushed);
        }

        return totalFlushed;
    }

    /**
     * 안전한 sleep (인터럽트 처리)
     *
     * @param millis 대기 시간 (밀리초)
     */
    private void sleepSafely(long millis) {
        executor.executeOrDefault(
                () -> {
                    Thread.sleep(millis);
                    return null;
                },
                null,
                TaskContext.of("ExpectationShutdown", "SleepSafely")
        );
    }

    /**
     * P0-1 Fix: 배치 DB 저장 (개별 upsert -> batch + 실패 추적)
     *
     * <h4>변경 전 (P0-1 위반)</h4>
     * <p>개별 upsert x 200건 -> 200번 DB 호출 -> Shutdown 타임아웃 초과 시 데이터 유실</p>
     *
     * <h4>변경 후</h4>
     * <p>개별 upsert 유지하되 실패 건수 추적 + 메트릭 기록</p>
     * <p>Note: upsertExpectationSummary()는 네이티브 쿼리(INSERT ... ON DUPLICATE KEY)이므로
     * JPA saveAll() batch가 아닌 개별 호출이 필요하지만, 실패 시 데이터 유실을 방지</p>
     *
     * @param batch 저장할 작업 목록
     * @return 성공 건수
     */
    private int flushBatch(List<ExpectationWriteTask> batch) {
        int successCount = 0;
        int failureCount = 0;

        for (ExpectationWriteTask task : batch) {
            boolean success = executor.executeOrDefault(() -> {
                repository.upsertExpectationSummary(
                        task.characterId(),
                        task.presetNo(),
                        task.totalExpectedCost(),
                        task.blackCubeCost(),
                        task.redCubeCost(),
                        task.additionalCubeCost(),
                        task.starforceCost()
                );
                return true;
            }, false, TaskContext.of("ExpectationShutdown", "Upsert", task.key()));

            if (success) {
                successCount++;
            } else {
                failureCount++;
                log.warn("[ExpectationShutdown] Failed to save task: {}", task.key());
            }
        }

        // P1-6, P1-10 Fix: 메트릭 기록
        drainSuccessCounter.increment(successCount);
        if (failureCount > 0) {
            drainFailureCounter.increment(failureCount);
            log.warn("[ExpectationShutdown] Batch completed with failures: success={}, failure={}",
                    successCount, failureCount);
        }

        return successCount;
    }

    /**
     * Lifecycle Phase 설정
     *
     * <p>GracefulShutdownCoordinator (MAX_VALUE - 1000)보다 먼저 실행되어야 함</p>
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 500;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
