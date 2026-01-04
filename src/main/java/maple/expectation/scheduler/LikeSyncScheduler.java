package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성 확보
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.service.v2.LikeSyncService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LikeSyncScheduler {

    private final LikeSyncService likeSyncService;
    private final LockStrategy lockStrategy;
    private final LogicExecutor executor; // ✅ 지능형 실행기 주입

    /**
     * ✅ L1 -> L2 Flush
     * 단순 실행이지만 LogicExecutor를 통해 실행 시간 및 성공 여부를 추적합니다.
     */
    @Scheduled(fixedRate = 1000)
    public void localFlush() {
        executor.executeVoid(
                likeSyncService::flushLocalToRedis,
                TaskContext.of("Scheduler", "LocalFlush") //
        );
    }

    /**
     * ✅ L2 -> L3 DB 동기화
     *  try-catch를 제거하고 executeWithRecovery를 통해 락 경합 시나리오를 평탄화했습니다.
     */
    @Scheduled(fixedRate = 3000)
    public void globalSync() {
        TaskContext context = TaskContext.of("Scheduler", "GlobalSync");

        // [패턴 5] executeWithRecovery: 락 획득 실패는 정상 시나리오로, 실제 장애는 로그로 분리
        executor.executeWithRecovery(() -> {
            lockStrategy.executeWithLock("like-db-sync-lock", 0, 10, () -> {
                likeSyncService.syncRedisToDatabase();
                return null;
            });
            return null;
        }, (e) -> {
            handleSyncFailure(e); // 락 경합 및 장애 대응 로직 격리
            return null;
        }, context);
    }

    /**
     * 헬퍼: 동기화 실패 대응 (평탄화 보조)
     * 락 획득 실패는 스케줄러 특성상 빈번하므로 로그 수준을 조절합니다.
     */
    private void handleSyncFailure(Throwable t) {
        // [분기 1] DistributedLockException: 다른 서버가 작업 중인 경우 (정상)
        if (t instanceof maple.expectation.global.error.exception.DistributedLockException) {
            log.debug("ℹ️ [LikeSync] 락 획득 스킵: 다른 서버가 동기화 진행 중입니다.");
            return;
        }

        // [분기 2] 그 외 실제 장애 (DB 장애 등)
        log.error("⚠️ [LikeSync] 글로벌 동기화 중 에러 발생: {}", t.getMessage());
    }
}