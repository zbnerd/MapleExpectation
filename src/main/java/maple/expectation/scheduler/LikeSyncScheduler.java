package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.global.queue.like.PartitionedFlushStrategy;
import maple.expectation.service.v2.LikeRelationSyncService;
import maple.expectation.service.v2.LikeSyncService;
import maple.expectation.service.v2.cache.LikeBufferStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 좋아요 동기화 스케줄러 (#271 V5 Stateless Architecture)
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
 *
 * <h3>P0-10: Flush Race 해결</h3>
 * <p>Redis 모드에서 PartitionedFlushStrategy를 사용하여 파티션 기반 분산 Flush를 수행합니다.
 * 각 인스턴스가 자신이 담당하는 파티션만 Flush하여 중복 Flush를 방지합니다.</p>
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
    private final LikeBufferStrategy likeBufferStrategy;

    /**
     * PartitionedFlushStrategy (Redis 모드에서만 주입)
     *
     * <p>In-Memory 모드에서는 null이며, Redis 모드에서만 Bean이 생성됩니다.</p>
     */
    @Autowired(required = false)
    private PartitionedFlushStrategy partitionedFlushStrategy;

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
     *
     * <h4>P0-10: Partitioned Flush (Redis 모드)</h4>
     * <p>Redis 모드에서는 PartitionedFlushStrategy를 사용하여
     * 파티션 기반 분산 Flush를 수행합니다.</p>
     */
    @Scheduled(fixedRate = 3000)
    public void globalSyncCount() {
        TaskContext context = TaskContext.of("Scheduler", "GlobalSync.Count");

        // P0-10: Redis 모드에서 Partitioned Flush 사용
        if (isRedisMode() && partitionedFlushStrategy != null) {
            executor.executeOrCatch(() -> {
                partitionedFlushStrategy.flushAssignedPartitions();
                return null;
            }, e -> {
                handleSyncFailure(e, "PartitionedFlush");
                return null;
            }, context);
            return;
        }

        // In-Memory 모드: 기존 락 기반 동기화
        // P1-3: leaseTime 10s → 30s (워크로드 증가 대응, #271 V5)
        executor.executeOrCatch(() -> {
            lockStrategy.executeWithLock("like-db-sync-lock", 0, 30, () -> {
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
     * Redis 모드 여부 확인
     */
    private boolean isRedisMode() {
        return likeBufferStrategy.getType() == LikeBufferStrategy.StrategyType.REDIS;
    }

    /**
     * L2 → L3 DB 동기화 (likeRelation)
     *
     * <p>P1-10: Count(3s)와 Relation(5s)의 동기화 주기를 stagger하여
     * 동시 락 경합을 방지합니다.</p>
     */
    @Scheduled(fixedRate = 5000)
    public void globalSyncRelation() {
        TaskContext context = TaskContext.of("Scheduler", "GlobalSync.Relation");

        // P1-3: leaseTime 10s → 30s (워크로드 증가 대응, #271 V5)
        executor.executeOrCatch(() -> {
            lockStrategy.executeWithLock("like-relation-sync-lock", 0, 30, () -> {
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