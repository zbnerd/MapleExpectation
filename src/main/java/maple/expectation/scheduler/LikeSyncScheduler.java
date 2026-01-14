package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.service.v2.LikeRelationSyncService;
import maple.expectation.service.v2.LikeSyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 좋아요 동기화 스케줄러
 *
 * <p>두 가지 버퍼 동기화:
 * <ul>
 *   <li>likeCount: LikeSyncService (숫자 카운트)</li>
 *   <li>likeRelation: LikeRelationSyncService (관계 데이터)</li>
 * </ul>
 * </p>
 *
 * <p>동기화 주기:
 * <ul>
 *   <li>L1 → L2: 1초 (로컬 → Redis)</li>
 *   <li>L2 → L3: 3초 (Redis → DB)</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "scheduler.like-sync.enabled",
        havingValue = "true",
        matchIfMissing = true  // 프로덕션에서는 기본 활성화
)
public class LikeSyncScheduler {

    private final LikeSyncService likeSyncService;
    private final LikeRelationSyncService likeRelationSyncService;
    private final LockStrategy lockStrategy;
    private final LogicExecutor executor;

    /**
     * L1 → L2 Flush (likeCount + likeRelation)
     */
    @Scheduled(fixedRate = 1000)
    public void localFlush() {
        // likeCount 버퍼 동기화
        executor.executeVoid(
                likeSyncService::flushLocalToRedis,
                TaskContext.of("Scheduler", "LocalFlush.Count")
        );

        // likeRelation 버퍼 동기화
        executor.executeVoid(
                likeRelationSyncService::flushLocalToRedis,
                TaskContext.of("Scheduler", "LocalFlush.Relation")
        );
    }

    /**
     * L2 → L3 DB 동기화 (likeCount)
     */
    @Scheduled(fixedRate = 3000)
    public void globalSyncCount() {
        TaskContext context = TaskContext.of("Scheduler", "GlobalSync.Count");

        executor.executeOrCatch(() -> {
            lockStrategy.executeWithLock("like-db-sync-lock", 0, 10, () -> {
                likeSyncService.syncRedisToDatabase();
                return null;
            });
            return null;
        }, e -> {
            handleSyncFailure(e, "Count");
            return null;
        }, context);
    }

    /**
     * L2 → L3 DB 동기화 (likeRelation)
     */
    @Scheduled(fixedRate = 3000)
    public void globalSyncRelation() {
        TaskContext context = TaskContext.of("Scheduler", "GlobalSync.Relation");

        executor.executeOrCatch(() -> {
            lockStrategy.executeWithLock("like-relation-sync-lock", 0, 10, () -> {
                likeRelationSyncService.syncRedisToDatabase();
                return null;
            });
            return null;
        }, e -> {
            handleSyncFailure(e, "Relation");
            return null;
        }, context);
    }

    /**
     * 동기화 실패 대응
     */
    private void handleSyncFailure(Throwable t, String syncType) {
        if (t instanceof maple.expectation.global.error.exception.DistributedLockException) {
            log.debug("ℹ️ [LikeSync.{}] 락 획득 스킵: 다른 서버가 동기화 진행 중", syncType);
            return;
        }
        log.error("⚠️ [LikeSync.{}] 동기화 중 에러 발생: {}", syncType, t.getMessage());
    }
}