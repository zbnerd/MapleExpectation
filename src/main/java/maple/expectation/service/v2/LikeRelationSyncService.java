package maple.expectation.service.v2;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.CharacterLike;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.CharacterLikeRepository;
import maple.expectation.service.v2.cache.LikeRelationBuffer;
import maple.expectation.service.v2.cache.LikeRelationBufferStrategy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좋아요 관계 동기화 서비스 (Redis → DB 배치)
 *
 * <p>기존 LikeSyncService와 동일한 패턴:
 *
 * <ul>
 *   <li>L1 → L2 동기화 (스케줄러 호출)
 *   <li>L2 → L3 (DB) 배치 동기화
 * </ul>
 *
 * <p>CLAUDE.md 섹션 17: TieredCache Write Order (L2 → L3)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeRelationSyncService {

  private final LikeRelationBufferStrategy likeRelationBuffer;
  private final CharacterLikeRepository characterLikeRepository;
  private final LogicExecutor executor;
  private final maple.expectation.config.BatchProperties batchProperties;

  /**
   * L1 → L2 동기화 (스케줄러 호출)
   *
   * <p>In-Memory 전략에서만 의미있습니다. Redis 전략에서는 L1이 없으므로 no-op입니다.
   */
  public void flushLocalToRedis() {
    // Strategy 패턴: In-Memory 구현체만 flushLocalToRedis() 보유
    if (likeRelationBuffer instanceof LikeRelationBuffer inMemoryBuffer) {
      inMemoryBuffer.flushLocalToRedis();
    }
    // Redis 전략은 L1이 없으므로 no-op
  }

  /**
   * L2 → DB 배치 동기화
   *
   * <p>흐름:
   *
   * <ol>
   *   <li>Pending Set에서 관계 키 조회
   *   <li>배치 단위로 DB INSERT
   *   <li>성공 시 Pending Set에서 제거
   *   <li>UNIQUE 위반은 무시 (이미 동기화됨)
   * </ol>
   */
  @ObservedTransaction("scheduler.like.relation_sync")
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public SyncResult syncRedisToDatabase() {
    int pendingSize = likeRelationBuffer.getPendingSize();

    if (pendingSize == 0) {
      return SyncResult.empty();
    }

    log.info("📤 [LikeRelationSync] 동기화 시작: 최대 {}건 예상", pendingSize);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger skipCount = new AtomicInteger(0);
    AtomicInteger failCount = new AtomicInteger(0);

    // 배치 단위로 원자적 fetch + remove
    Set<String> batch;
    while (!(batch = likeRelationBuffer.fetchAndRemovePending(batchProperties.likeRelationSyncSize())).isEmpty()) {
      processBatch(batch, successCount, skipCount, failCount);
    }

    SyncResult result = new SyncResult(successCount.get(), skipCount.get(), failCount.get());
    log.info("📥 [LikeRelationSync] 동기화 완료: {}", result);

    return result;
  }

  private void processBatch(
      Set<String> batch,
      AtomicInteger successCount,
      AtomicInteger skipCount,
      AtomicInteger failCount) {
    for (String relationKey : batch) {
      processRelationKey(relationKey, successCount, skipCount, failCount);
    }
  }

  private void processRelationKey(
      String relationKey,
      AtomicInteger successCount,
      AtomicInteger skipCount,
      AtomicInteger failCount) {
    TaskContext context = TaskContext.of("LikeRelationSync", "SaveToDb", relationKey);

    executor.executeOrCatch(
        () -> {
          String[] parts = likeRelationBuffer.parseRelationKey(relationKey);
          if (parts.length != 2) {
            log.warn("⚠️ [LikeRelationSync] 잘못된 관계 키 형식: {}", relationKey);
            skipCount.incrementAndGet();
            return null;
          }

          String accountId = parts[0];
          String targetOcid = parts[1];

          // DB 저장 시도
          saveToDatabase(accountId, targetOcid);
          successCount.incrementAndGet();

          return null;
        },
        e -> {
          if (e instanceof DataIntegrityViolationException) {
            // UNIQUE 위반 = 이미 동기화됨 (정상)
            skipCount.incrementAndGet();
            log.debug("🔄 [LikeRelationSync] 이미 동기화됨: {}", relationKey);
          } else {
            // 실제 오류 → 재처리를 위해 다시 추가
            failCount.incrementAndGet();
            log.error("❌ [LikeRelationSync] DB 저장 실패: {}", relationKey, e);
          }
          return null;
        },
        context);
  }

  private void saveToDatabase(String accountId, String targetOcid) {
    // 사전 체크로 불필요한 INSERT 방지
    if (characterLikeRepository.existsByTargetOcidAndLikerAccountId(targetOcid, accountId)) {
      throw new DataIntegrityViolationException("Already exists");
    }

    CharacterLike like = CharacterLike.of(targetOcid, accountId);
    characterLikeRepository.save(like);
  }

  /** 동기화 결과 */
  public record SyncResult(int success, int skipped, int failed) {
    public static SyncResult empty() {
      return new SyncResult(0, 0, 0);
    }

    @Override
    public String toString() {
      return String.format("성공=%d, 스킵=%d, 실패=%d", success, skipped, failed);
    }
  }
}
