package maple.expectation.global.shutdown;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성 확보
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.global.shutdown.dto.FlushResult;
import maple.expectation.global.shutdown.dto.ShutdownData;
import maple.expectation.service.v2.LikeSyncService;
import maple.expectation.service.v2.shutdown.EquipmentPersistenceTracker;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot Graceful Shutdown 조정자 (LogicExecutor 평탄화 완료)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GracefulShutdownCoordinator implements SmartLifecycle {

    private final LikeSyncService likeSyncService;
    private final LockStrategy lockStrategy;
    private final EquipmentPersistenceTracker equipmentPersistenceTracker;
    private final ShutdownDataPersistenceService shutdownDataPersistenceService;
    private final LogicExecutor executor; // ✅ 지능형 실행기 주입

    private volatile boolean running = false;

    @Override
    public void start() {
        this.running = true;
        log.debug("✅ [Graceful Shutdown Coordinator] Started");
    }

    /**
     * ✅  stop() 메서드의 try-finally를 executeWithFinally로 대체
     */
    @Override
    public void stop() {
        TaskContext context = TaskContext.of("Shutdown", "MainProcess"); //

        executor.executeWithFinally(
                () -> {
                    log.warn("========= [System Shutdown] 종료 절차 시작 =========");

                    // 1. Equipment 비동기 저장 작업 완료 대기
                    ShutdownData backupData = waitForEquipmentPersistence();

                    // 2. 로컬 좋아요 버퍼 Flush
                    backupData = flushLikeBuffer(backupData);

                    // 3. 리더 서버인 경우 DB 최종 동기화
                    syncRedisToDatabase();

                    // 4. 백업 데이터 최종 저장
                    if (backupData != null && !backupData.isEmpty()) {
                        shutdownDataPersistenceService.saveShutdownData(backupData);
                    }
                    return null;
                },
                () -> {
                    this.running = false; //
                    log.warn("========= [System Shutdown] 종료 완료 =========");
                },
                context
        );
    }

    /**
     * Equipment 저장 대기 (관측성 추가)
     */
    private ShutdownData waitForEquipmentPersistence() {
        return executor.execute(() -> {
            log.info("▶️ [1/4] Equipment 비동기 저장 작업 완료 대기 중...");
            boolean allCompleted = equipmentPersistenceTracker.awaitAllCompletion(Duration.ofSeconds(20));

            if (!allCompleted) {
                List<String> pendingOcids = equipmentPersistenceTracker.getPendingOcids();
                log.warn("⚠️ Equipment 저장 미완료 항목: {}건", pendingOcids.size());
                return new ShutdownData(LocalDateTime.now(), shutdownDataPersistenceService.getInstanceId(), Map.of(), pendingOcids);
            }

            log.info("✅ 모든 Equipment 저장 작업 완료.");
            return ShutdownData.empty(shutdownDataPersistenceService.getInstanceId());
        }, TaskContext.of("Shutdown", "WaitEquipment"));
    }

    /**
     * ✅  try-catch를 executeWithRecovery로 대체
     */
    private ShutdownData flushLikeBuffer(ShutdownData backupData) {
        return executor.executeWithRecovery(
                () -> {
                    log.info("▶️ [2/4] 로컬 좋아요 버퍼 Flush 중...");
                    FlushResult result = likeSyncService.flushLocalToRedisWithFallback();
                    log.info("✅ 로컬 좋아요 버퍼 Flush 완료: Redis {}건, 파일 백업 {}건",
                            result.redisSuccessCount(), result.fileBackupCount());
                    return backupData;
                },
                (e) -> {
                    log.error("❌ 로컬 Flush 중 예상치 못한 오류 발생", e);
                    return backupData; // 실패해도 기존 데이터 반환하여 프로세스 유지
                },
                TaskContext.of("Shutdown", "FlushLikes")
        );
    }

    /**
     * ✅  복잡한 다중 catch 로직을 executeWithRecovery 내 분기 처리로 평탄화
     */
    private void syncRedisToDatabase() {
        executor.executeWithRecovery(
                () -> {
                    log.info("▶️ [3/4] DB 최종 동기화 시도 중...");
                    lockStrategy.executeWithLock("like-db-sync-lock", 3, 10, () -> {
                        likeSyncService.syncRedisToDatabase();
                        return null;
                    });
                    log.info("✅ DB 최종 동기화 완료.");
                    return null;
                },
                (e) -> {
                    // [분기 1] 락 획득 실패 시 (정상 시나리오)
                    if (e instanceof maple.expectation.global.error.exception.DistributedLockException) {
                        log.info("ℹ️ [Shutdown Sync] DB 동기화 스킵: 다른 서버가 처리 중입니다.");
                    }
                    // [분기 2] 기타 장애 (로그 남기고 진행)
                    else {
                        log.warn("⚠️ [Shutdown Sync] DB 동기화 실패: {}", e.getMessage());
                    }
                    return null;
                },
                TaskContext.of("Shutdown", "SyncToDb")
        );
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int getPhase() { return Integer.MAX_VALUE - 1000; }

    @Override
    public boolean isAutoStartup() { return true; }
}