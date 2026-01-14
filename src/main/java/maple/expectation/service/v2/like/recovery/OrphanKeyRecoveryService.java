package maple.expectation.service.v2.like.recovery;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.service.v2.like.strategy.AtomicFetchStrategy;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orphan Key 복구 서비스 (JVM 크래시 대응)
 *
 * <p>금융수준 안전 설계:
 * <ul>
 *   <li>서버 시작 시 미처리 임시 키 자동 검색</li>
 *   <li>임시 키 데이터를 원본 키로 복원</li>
 *   <li>데이터 유실 제로 보장</li>
 * </ul>
 * </p>
 *
 * <p>동작 시나리오:
 * <ol>
 *   <li>JVM 크래시 발생 → 임시 키에 데이터 잔존</li>
 *   <li>서버 재시작 → @PostConstruct로 복구 로직 실행</li>
 *   <li>pattern 검색으로 orphan key 발견</li>
 *   <li>restore()로 원본 키에 데이터 복원</li>
 * </ol>
 * </p>
 *
 * @since 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanKeyRecoveryService {

    /**
     * Hash Tag 패턴으로 Orphan Key 검색
     *
     * <p>Redis Cluster 호환: {buffer:likes}가 Hash Tag</p>
     */
    private static final String ORPHAN_KEY_PATTERN = "{buffer:likes}:sync:*";

    /**
     * 원본 키 (Hash Tag 패턴)
     */
    private static final String SOURCE_KEY = "{buffer:likes}";

    private final RedissonClient redissonClient;
    private final AtomicFetchStrategy atomicFetchStrategy;
    private final LogicExecutor executor;

    @Value("${like.sync.recovery.enabled:true}")
    private boolean recoveryEnabled;

    /**
     * 서버 시작 시 Orphan Key 복구
     *
     * <p>@PostConstruct로 자동 실행</p>
     *
     * <p>금융수준 안전 설계:
     * <ul>
     *   <li>복구 실패해도 애플리케이션 시작은 보장</li>
     *   <li>executeOrCatch 사용으로 예외 격리</li>
     *   <li>실패 시 로그 기록 (다음 시작 시 재시도)</li>
     * </ul>
     * </p>
     */
    @PostConstruct
    public void recoverOrphanKeys() {
        if (!recoveryEnabled) {
            log.info("Orphan key recovery is disabled");
            return;
        }

        // CRITICAL FIX: executeOrCatch 사용 - 복구 실패해도 앱 시작 보장
        executor.executeOrCatch(
                () -> {
                    doRecoverOrphanKeys();
                    return null;
                },
                e -> {
                    log.error("⚠️ [Startup] Orphan key recovery failed. " +
                            "Data may be recovered on next restart or expire by TTL.", e);
                    return null;
                },
                TaskContext.of("LikeSync", "OrphanRecovery", "startup")
        );
    }

    /**
     * 수동 복구 트리거 (관리 API용)
     *
     * @return 복구된 키 수
     */
    public int triggerManualRecovery() {
        return executor.executeOrDefault(
                this::doRecoverOrphanKeys,
                0,
                TaskContext.of("LikeSync", "ManualOrphanRecovery", "trigger")
        );
    }

    // ========== Private Methods ==========

    private int doRecoverOrphanKeys() {
        RKeys keys = redissonClient.getKeys();
        Iterable<String> orphanKeys = keys.getKeysByPattern(ORPHAN_KEY_PATTERN);

        AtomicInteger recoveredCount = new AtomicInteger(0);

        for (String orphanKey : orphanKeys) {
            recoverSingleKey(orphanKey, recoveredCount);
        }

        int count = recoveredCount.get();
        if (count > 0) {
            log.warn("Orphan key recovery completed: {} keys recovered", count);
        } else {
            log.debug("No orphan keys found during startup recovery");
        }

        return count;
    }

    private void recoverSingleKey(String orphanKey, AtomicInteger recoveredCount) {
        executor.executeOrCatch(
                () -> {
                    atomicFetchStrategy.restore(orphanKey, SOURCE_KEY);
                    recoveredCount.incrementAndGet();
                    log.info("Orphan key recovered: {} -> {}", orphanKey, SOURCE_KEY);
                    return null;
                },
                e -> {
                    log.error("Orphan key recovery FAILED: {} (will retry on next restart or expire by TTL)",
                            orphanKey, e);
                    return null;
                },
                TaskContext.of("LikeSync", "RecoverSingleKey", orphanKey)
        );
    }
}
