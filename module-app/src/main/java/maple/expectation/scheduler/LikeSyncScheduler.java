package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.port.out.LikeBufferStrategy;
import maple.expectation.error.exception.DistributedLockException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.lock.LockStrategy;
import maple.expectation.infrastructure.queue.like.PartitionedFlushStrategy;
import maple.expectation.service.v2.LikeRelationSyncService;
import maple.expectation.service.v2.LikeSyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 좋아요 동기화 스케줄러 (#271 V5 Stateless Architecture)
 *
 * <p>두 가지 버퍼 동기화:
 *
 * <ul>
 *   <li>likeCount: LikeSyncService (숫자 카운트)
 *   <li>likeRelation: LikeRelationSyncService (관계 데이터)
 * </ul>
 *
 * <p>동기화 주기:
 *
 * <ul>
 *   <li>L1 → L2: 1초 (로컬 → Redis)
 *   <li>L2 → L3: 3초 (Redis → DB)
 * </ul>
 *
 * <h3>P0-10: Flush Race 해결</h3>
 *
 * <p>Redis 모드에서 PartitionedFlushStrategy를 사용하여 파티션 기반 분산 Flush를 수행합니다. 각 인스턴스가 자신이 담당하는 파티션만
 * Flush하여 중복 Flush를 방지합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "scheduler.like-sync.enabled",
    havingValue = "true",
    matchIfMissing = true // 프로덕션에서는 기본 활성화
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
   * <p>In-Memory 모드에서는 null이며, Redis 모드에서만 Bean이 생성됩니다.
   *
   * <h4>Issue #283 P1-8: Constructor Injection (CLAUDE.md Section 6)</h4>
   *
   * <p>@Autowired(required=false) 대신 @Nullable + 생성자 주입 패턴 사용
   */
  @Nullable private final PartitionedFlushStrategy partitionedFlushStrategy;

  /**
   * L1 → L2 Flush (likeCount + likeRelation)
   *
   * <h4>분산 환경 안전 (Issue #283 P1-8)</h4>
   *
   * <p><b>분산 락 미적용 사유</b>: Redis 모드에서 각 인스턴스는 자신의 로컬 Caffeine L1 캐시를 Redis L2로 Flush합니다. 이는 인스턴스별
   * 독립적인 작업이므로 분산 락이 불필요합니다.
   *
   * <p><b>중복 방지 메커니즘</b>:
   *
   * <ul>
   *   <li>각 인스턴스의 L1 캐시는 독립적으로 관리됨
   *   <li>L1 → L2 Flush는 인스턴스별 로컬 작업
   *   <li>L2 → L3 DB 동기화만 분산 락 필요 (globalSyncCount/Relation)
   * </ul>
   *
   * <h4>Issue #344: fixedRate → fixedDelay</h4>
   *
   * <p>이전 실행 완료 후 3초 대기하여 스케줄러 겹침 방지, Redis/MySQL 락 경합 감소
   */
  @Scheduled(fixedDelay = 3000)
  public void localFlush() {
    // likeCount 버퍼 동기화
    executor.executeVoid(
        likeSyncService::flushLocalToRedis, TaskContext.of("Scheduler", "LocalFlush.Count"));

    // likeRelation 버퍼 동기화
    executor.executeVoid(
        likeRelationSyncService::flushLocalToRedis,
        TaskContext.of("Scheduler", "LocalFlush.Relation"));
  }

  /**
   * L2 → L3 DB 동기화 (likeCount)
   *
   * <h4>P0-10: Partitioned Flush (Redis 모드)</h4>
   *
   * <p>Redis 모드에서는 PartitionedFlushStrategy를 사용하여 파티션 기반 분산 Flush를 수행합니다.
   *
   * <h4>Issue #344: fixedRate → fixedDelay</h4>
   *
   * <p>이전 실행 완료 후 5초 대기하여 DB 동기화 여유 확보, MySQL 커넥션 풀 부하 감소
   */
  @Scheduled(fixedDelay = 5000)
  public void globalSyncCount() {
    TaskContext context = TaskContext.of("Scheduler", "GlobalSync.Count");

    // P0-10: Redis 모드에서 Partitioned Flush 사용
    if (isRedisMode() && partitionedFlushStrategy != null) {
      executor.executeOrCatch(
          () -> {
            partitionedFlushStrategy.flushAssignedPartitions();
            return null;
          },
          e -> {
            handleSyncFailure(e, "PartitionedFlush");
            return null;
          },
          context);
      return;
    }

    // In-Memory 모드: 기존 락 기반 동기화
    // P1-3: leaseTime 10s → 30s (워크로드 증가 대응, #271 V5)
    executor.executeOrCatch(
        () -> {
          lockStrategy.executeWithLock(
              "like-db-sync-lock",
              0,
              30,
              () -> {
                likeSyncService.syncRedisToDatabase();
                return null;
              });
          return null;
        },
        e -> {
          handleSyncFailure(e, "Count");
          return null;
        },
        context);
  }

  /** Redis 모드 여부 확인 */
  private boolean isRedisMode() {
    return likeBufferStrategy.getType() == LikeBufferStrategy.StrategyType.REDIS;
  }

  /**
   * L2 → L3 DB 동기화 (likeRelation)
   *
   * <p>P1-10: Count(3s)와 Relation(5s)의 동기화 주기를 stagger하여 동시 락 경합을 방지합니다.
   *
   * <h4>Issue #344: fixedRate → fixedDelay</h4>
   *
   * <p>이전 실행 완료 후 10초 대기, Count(5s)와 stagger하여 동시 락 경합 최소화
   */
  @Scheduled(fixedDelay = 10000)
  public void globalSyncRelation() {
    TaskContext context = TaskContext.of("Scheduler", "GlobalSync.Relation");

    // P1-3: leaseTime 10s → 30s (워크로드 증가 대응, #271 V5)
    executor.executeOrCatch(
        () -> {
          lockStrategy.executeWithLock(
              "like-relation-sync-lock",
              0,
              30,
              () -> {
                likeRelationSyncService.syncRedisToDatabase();
                return null;
              });
          return null;
        },
        e -> {
          handleSyncFailure(e, "Relation");
          return null;
        },
        context);
  }

  /** 동기화 실패 대응 */
  private void handleSyncFailure(Throwable t, String syncType) {
    if (t instanceof DistributedLockException) {
      log.debug("ℹ️ [LikeSync.{}] 락 획득 스킵: 다른 서버가 동기화 진행 중", syncType);
      return;
    }
    log.error("⚠️ [LikeSync.{}] 동기화 중 에러 발생: {}", syncType, t.getMessage());
  }
}
