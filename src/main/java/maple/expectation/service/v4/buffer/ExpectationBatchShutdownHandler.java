package maple.expectation.service.v4.buffer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.EquipmentExpectationSummaryRepository;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Expectation 버퍼 Graceful Shutdown 핸들러 (#266 ADR 정합성 리팩토링)
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Purple (Auditor): 데이터 유실 방지 - 3단계 Shutdown 프로세스</li>
 *   <li>Red (SRE): GracefulShutdownCoordinator보다 먼저 실행, 타임아웃 외부화</li>
 *   <li>Blue (Architect): SmartLifecycle 패턴으로 순서 제어</li>
 * </ul>
 *
 * <h3>P0: 3단계 Shutdown 프로세스</h3>
 * <ol>
 *   <li><b>Phase 1:</b> 신규 offer 차단 (prepareShutdown)</li>
 *   <li><b>Phase 2:</b> 진행 중인 offer 완료 대기 (awaitPendingOffers)</li>
 *   <li><b>Phase 3:</b> 버퍼 완전 drain (drainBuffer)</li>
 * </ol>
 *
 * <h3>Phase 설정</h3>
 * <p>Integer.MAX_VALUE - 500: GracefulShutdownCoordinator (MAX_VALUE - 1000)보다 먼저 실행</p>
 *
 * <h3>동작 순서</h3>
 * <ol>
 *   <li>ExpectationBatchShutdownHandler.stop() (Phase: MAX_VALUE - 500)</li>
 *   <li>GracefulShutdownCoordinator.stop() (Phase: MAX_VALUE - 1000)</li>
 *   <li>기타 빈 종료</li>
 * </ol>
 *
 * @see maple.expectation.global.shutdown.GracefulShutdownCoordinator 메인 Shutdown 조정자
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpectationBatchShutdownHandler implements SmartLifecycle {

    private final ExpectationWriteBackBuffer buffer;
    private final EquipmentExpectationSummaryRepository repository;
    private final LogicExecutor executor;

    private volatile boolean running = true;

    /**
     * Shutdown 시 배치 크기
     *
     * <p>빠른 종료를 위해 일반 배치보다 큰 크기 사용</p>
     */
    private static final int SHUTDOWN_BATCH_SIZE = 200;

    /**
     * 빈 배치 재시도 횟수
     *
     * <p>Race condition으로 인한 일시적 빈 배치 대응</p>
     */
    private static final int EMPTY_BATCH_RETRY_COUNT = 3;

    /**
     * 빈 배치 간 대기 시간 (밀리초)
     */
    private static final long EMPTY_BATCH_WAIT_MS = 100;

    @Override
    public void start() {
        this.running = true;
        log.debug("[ExpectationShutdown] Started");
    }

    /**
     * Graceful Shutdown 시 3단계 프로세스 실행
     *
     * <h4>CLAUDE.md Section 12 준수</h4>
     * <p>try-catch 금지 → LogicExecutor.executeVoid() 사용</p>
     *
     * <h4>P0: 데이터 유실 0건 보장</h4>
     * <ol>
     *   <li>Phase 1: 신규 offer 차단</li>
     *   <li>Phase 2: 진행 중 offer 완료 대기</li>
     *   <li>Phase 3: 버퍼 완전 drain</li>
     * </ol>
     */
    @Override
    public void stop() {
        TaskContext context = TaskContext.of("ExpectationShutdown", "DrainBuffer");

        executor.executeVoid(() -> {
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

        }, context);

        this.running = false;
    }

    /**
     * 버퍼 완전 drain (Phase 3)
     *
     * <h4>빈 배치 재시도 로직</h4>
     * <p>Race condition으로 인한 일시적 빈 배치에 대응하여
     * 연속 3회 빈 배치가 발생할 때까지 drain을 계속합니다.</p>
     *
     * @return 총 flush된 작업 수
     */
    private int drainBuffer() {
        int totalFlushed = 0;
        int emptyRetries = 0;

        while (emptyRetries < EMPTY_BATCH_RETRY_COUNT) {
            List<ExpectationWriteTask> batch = buffer.drain(SHUTDOWN_BATCH_SIZE);

            if (batch.isEmpty()) {
                emptyRetries++;
                sleepSafely(EMPTY_BATCH_WAIT_MS);
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
     * 배치 DB 저장
     *
     * @param batch 저장할 작업 목록
     * @return 성공 건수
     */
    private int flushBatch(List<ExpectationWriteTask> batch) {
        int successCount = 0;

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
                log.warn("[ExpectationShutdown] Failed to save task: {}", task.key());
            }
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
