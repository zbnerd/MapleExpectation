package maple.expectation.service.v4.buffer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.EquipmentExpectationSummaryRepository;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Expectation 버퍼 Graceful Shutdown 핸들러 (#266)
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Purple (Auditor): 데이터 유실 방지 - Shutdown 시 버퍼 완전 drain</li>
 *   <li>Red (SRE): GracefulShutdownCoordinator보다 먼저 실행</li>
 *   <li>Blue (Architect): SmartLifecycle 패턴으로 순서 제어</li>
 * </ul>
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

    @Override
    public void start() {
        this.running = true;
        log.debug("[ExpectationShutdown] Started");
    }

    /**
     * Graceful Shutdown 시 버퍼 완전 drain
     *
     * <h4>CLAUDE.md Section 12 준수</h4>
     * <p>try-catch 금지 → LogicExecutor.executeVoid() 사용</p>
     */
    @Override
    public void stop() {
        TaskContext context = TaskContext.of("ExpectationShutdown", "DrainBuffer");

        executor.executeVoid(() -> {
            log.info("[ExpectationShutdown] Draining buffer... pending={}",
                    buffer.getPendingCount());

            int totalFlushed = 0;
            while (!buffer.isEmpty()) {
                List<ExpectationWriteTask> batch = buffer.drain(SHUTDOWN_BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }

                int batchFlushed = flushBatch(batch);
                totalFlushed += batchFlushed;
            }

            log.info("[ExpectationShutdown] Drain completed: {} tasks flushed", totalFlushed);
        }, context);

        this.running = false;
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
