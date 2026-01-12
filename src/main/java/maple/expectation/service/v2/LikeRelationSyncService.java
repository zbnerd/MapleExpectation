package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.domain.CharacterLike;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.CharacterLikeRepository;
import maple.expectation.service.v2.cache.LikeRelationBuffer;
import org.redisson.api.RSet;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 좋아요 관계 동기화 서비스 (Redis → DB 배치)
 *
 * <p>기존 LikeSyncService와 동일한 패턴:
 * <ul>
 *   <li>L1 → L2 동기화 (스케줄러 호출)</li>
 *   <li>L2 → L3 (DB) 배치 동기화</li>
 * </ul>
 * </p>
 *
 * <p>CLAUDE.md 섹션 17: TieredCache Write Order (L2 → L3)</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeRelationSyncService {

    private static final int BATCH_SIZE = 100;

    private final LikeRelationBuffer likeRelationBuffer;
    private final CharacterLikeRepository characterLikeRepository;
    private final LogicExecutor executor;

    /**
     * L1 → L2 동기화 (스케줄러 호출)
     */
    public void flushLocalToRedis() {
        likeRelationBuffer.flushLocalToRedis();
    }

    /**
     * L2 → DB 배치 동기화
     *
     * <p>흐름:
     * <ol>
     *   <li>Pending Set에서 관계 키 조회</li>
     *   <li>배치 단위로 DB INSERT</li>
     *   <li>성공 시 Pending Set에서 제거</li>
     *   <li>UNIQUE 위반은 무시 (이미 동기화됨)</li>
     * </ol>
     * </p>
     */
    @ObservedTransaction("scheduler.like.relation_sync")
    @Transactional
    public SyncResult syncRedisToDatabase() {
        RSet<String> pendingSet = likeRelationBuffer.getPendingSet();
        Set<String> pendingKeys = pendingSet.readAll();

        if (pendingKeys.isEmpty()) {
            return SyncResult.empty();
        }

        log.info("📤 [LikeRelationSync] 동기화 시작: {}건", pendingKeys.size());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 배치 처리
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        for (String relationKey : pendingKeys) {
            batch.add(relationKey);

            if (batch.size() >= BATCH_SIZE) {
                processBatch(batch, pendingSet, successCount, skipCount, failCount);
                batch.clear();
            }
        }

        // 남은 배치 처리
        if (!batch.isEmpty()) {
            processBatch(batch, pendingSet, successCount, skipCount, failCount);
        }

        SyncResult result = new SyncResult(successCount.get(), skipCount.get(), failCount.get());
        log.info("📥 [LikeRelationSync] 동기화 완료: {}", result);

        return result;
    }

    private void processBatch(List<String> batch, RSet<String> pendingSet,
                              AtomicInteger successCount, AtomicInteger skipCount, AtomicInteger failCount) {
        for (String relationKey : batch) {
            processRelationKey(relationKey, pendingSet, successCount, skipCount, failCount);
        }
    }

    private void processRelationKey(String relationKey, RSet<String> pendingSet,
                                    AtomicInteger successCount, AtomicInteger skipCount, AtomicInteger failCount) {
        TaskContext context = TaskContext.of("LikeRelationSync", "SaveToDb", relationKey);

        executor.executeOrCatch(
                () -> {
                    String[] parts = likeRelationBuffer.parseRelationKey(relationKey);
                    if (parts.length != 2) {
                        log.warn("⚠️ [LikeRelationSync] 잘못된 관계 키 형식: {}", relationKey);
                        pendingSet.remove(relationKey);
                        skipCount.incrementAndGet();
                        return null;
                    }

                    String fingerprint = parts[0];
                    String targetOcid = parts[1];

                    // DB 저장 시도
                    saveToDatabase(fingerprint, targetOcid);
                    pendingSet.remove(relationKey);
                    successCount.incrementAndGet();

                    return null;
                },
                e -> {
                    if (e instanceof DataIntegrityViolationException) {
                        // UNIQUE 위반 = 이미 동기화됨 (정상)
                        pendingSet.remove(relationKey);
                        skipCount.incrementAndGet();
                        log.debug("🔄 [LikeRelationSync] 이미 동기화됨: {}", relationKey);
                    } else {
                        // 실제 오류 → 다음 동기화에서 재시도
                        failCount.incrementAndGet();
                        log.error("❌ [LikeRelationSync] DB 저장 실패: {}", relationKey, e);
                    }
                    return null;
                },
                context
        );
    }

    private void saveToDatabase(String fingerprint, String targetOcid) {
        // 사전 체크로 불필요한 INSERT 방지
        if (characterLikeRepository.existsByTargetOcidAndLikerFingerprint(targetOcid, fingerprint)) {
            throw new DataIntegrityViolationException("Already exists");
        }

        CharacterLike like = CharacterLike.of(targetOcid, fingerprint);
        characterLikeRepository.save(like);
    }

    /**
     * 동기화 결과
     */
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
