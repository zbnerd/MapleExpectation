package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.redis.script.LikeAtomicOperations;
import maple.expectation.global.redis.script.LuaScripts;
import maple.expectation.global.shutdown.dto.FlushResult;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 좋아요 동기화 서비스
 *
 * <p>3-Layer Write-Back Cache 아키텍처의 동기화를 담당합니다.</p>
 *
 * <pre>
 * L1 (Caffeine) → L2 (Redis) → L3 (MySQL)
 * </pre>
 *
 * <h2>원자성 보장 (Issue #147)</h2>
 * <p>모든 Redis 연산은 Lua Script 기반 {@link LikeAtomicOperations}를 통해
 * 원자적으로 실행되어 데이터 유실을 방지합니다.</p>
 *
 * <h2>멱등성 보장</h2>
 * <p>Cleanup 연산은 중복 실행 시 카운터 중복 차감을 방지합니다.</p>
 *
 * @see LikeAtomicOperations 원자적 Redis 연산
 * @see LuaScripts Lua 스크립트 상수
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeSyncService {

    private final LikeBufferStorage likeBufferStorage;
    private final LikeSyncExecutor syncExecutor;
    private final StringRedisTemplate redisTemplate;
    private final LikeAtomicOperations atomicOperations;
    private final Retry likeSyncRetry;
    private final ShutdownDataPersistenceService shutdownDataPersistenceService;
    private final LogicExecutor executor;

    /**
     * L1 → L2 원자적 전송
     *
     * <p>로컬 버퍼(Caffeine)의 모든 엔트리를 Redis로 원자적으로 전송합니다.</p>
     */
    public void flushLocalToRedis() {
        Map<String, AtomicLong> snapshot = likeBufferStorage.getCache().asMap();
        if (snapshot.isEmpty()) return;
        snapshot.forEach(this::processLocalBufferEntry);
    }

    /**
     * Graceful Shutdown용 L1 → L2 전송 (파일 백업 Fallback)
     *
     * <p>Redis 전송 실패 시 파일로 백업하여 데이터 유실을 방지합니다.</p>
     *
     * @return 전송 결과 (성공/백업 건수)
     */
    public FlushResult flushLocalToRedisWithFallback() {
        Map<String, AtomicLong> snapshot = likeBufferStorage.getCache().asMap();
        if (snapshot.isEmpty()) return FlushResult.empty();

        AtomicInteger redisSuccessCount = new AtomicInteger(0);
        AtomicInteger fileBackupCount = new AtomicInteger(0);

        snapshot.forEach((userIgn, atomicCount) -> {
            long count = atomicCount.getAndSet(0);
            if (count <= 0) return;

            transferOrBackup(userIgn, count, redisSuccessCount, fileBackupCount);
        });

        return new FlushResult(redisSuccessCount.get(), fileBackupCount.get());
    }

    /**
     * L2 → L3 동기화 (Redis → MySQL)
     *
     * <p>Redis의 모든 좋아요 데이터를 MySQL에 반영합니다.
     * 실패한 엔트리는 원본 버퍼로 복구됩니다.</p>
     */
    @ObservedTransaction("scheduler.like.redis_to_db")
    public void syncRedisToDatabase() {
        String tempKey = LuaScripts.Keys.SYNC_PREFIX + UUID.randomUUID();
        TaskContext context = TaskContext.of("LikeSync", "RedisToDb", tempKey);

        executor.executeWithFinally(
                () -> {
                    doSyncProcess(tempKey);
                    return null;
                },
                () -> cleanupTempKey(tempKey),
                context
        );
    }

    private void transferOrBackup(String userIgn, long count,
                                   AtomicInteger successCount, AtomicInteger backupCount) {
        boolean success = executor.executeOrDefault(
                () -> atomicOperations.atomicTransfer(userIgn, count),
                false,
                TaskContext.of("LikeSync", "ShutdownFlush", userIgn)
        );

        if (success) {
            successCount.incrementAndGet();
        } else {
            backupToFile(userIgn, count, backupCount);
        }
    }

    private void backupToFile(String userIgn, long count, AtomicInteger backupCount) {
        log.warn("⚠️ [Shutdown Flush] Redis 전송 실패, 파일 백업: {} ({}건)", userIgn, count);
        shutdownDataPersistenceService.appendLikeEntry(userIgn, count);
        backupCount.incrementAndGet();
    }

    private void doSyncProcess(String tempKey) {
        if (Boolean.FALSE.equals(redisTemplate.hasKey(LuaScripts.Keys.HASH))) return;

        var ops = redisTemplate.opsForHash();
        redisTemplate.rename(LuaScripts.Keys.HASH, tempKey);

        Map<Object, Object> entries = ops.entries(tempKey);
        if (entries.isEmpty()) {
            redisTemplate.delete(tempKey);
            return;
        }

        boolean needsCleanup = false;

        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String userIgn = (String) entry.getKey();
            long count = parseToLong(entry.getValue());

            boolean dbSuccess = syncWithRetry(userIgn, count);

            if (dbSuccess) {
                handleDbSyncSuccess(tempKey, userIgn, count);
                continue;
            }

            needsCleanup = handleDbSyncFailure(tempKey, userIgn, count) || needsCleanup;
        }

        if (!needsCleanup) {
            redisTemplate.delete(tempKey);
        }
    }

    private void handleDbSyncSuccess(String tempKey, String userIgn, long count) {
        long deleted = atomicOperations.atomicDeleteAndDecrement(tempKey, userIgn, count);
        if (deleted == 0) {
            log.debug("🔄 [Sync] 이미 처리된 엔트리 (멱등성): {} ({}건)", userIgn, count);
        }
    }

    private boolean handleDbSyncFailure(String tempKey, String userIgn, long count) {
        boolean compensated = atomicOperations.atomicCompensation(tempKey, userIgn, count);

        if (compensated) {
            log.warn("♻️ [Sync Recovery] DB 반영 실패로 원본 버퍼 복구: {} ({}건)", userIgn, count);
            return false;
        }

        log.error("‼️ [Sync Recovery] 원본 버퍼 복구 실패: {} ({}건) - cleanup에서 재시도", userIgn, count);
        return true;
    }

    private long parseToLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private void cleanupTempKey(String tempKey) {
        executor.executeVoid(() -> {
            if (Boolean.FALSE.equals(redisTemplate.hasKey(tempKey))) return;

            var ops = redisTemplate.opsForHash();
            Map<Object, Object> strandedEntries = ops.entries(tempKey);

            strandedEntries.forEach((key, value) -> {
                String userIgn = (String) key;
                long count = parseToLong(value);
                atomicOperations.atomicCompensation(tempKey, userIgn, count);
            });

            redisTemplate.delete(tempKey);
            log.info("♻️ [Sync Cleanup] 임시 키 데이터를 원본 버퍼로 병합 완료 ({}건)", strandedEntries.size());
        }, TaskContext.of("LikeSync", "Cleanup", tempKey));
    }

    private void processLocalBufferEntry(String userIgn, AtomicLong atomicCount) {
        long count = atomicCount.getAndSet(0);
        if (count <= 0) return;

        boolean success = executor.executeOrDefault(
                () -> atomicOperations.atomicTransfer(userIgn, count),
                false,
                TaskContext.of("LikeSync", "L1toL2", userIgn)
        );

        if (!success) {
            handleRedisFailure(userIgn, count);
        }
    }

    private void handleRedisFailure(String userIgn, long count) {
        log.error("🚑 [Redis Down] L2 전송 실패. DB 직접 반영 시도: {}", userIgn);
        executor.executeOrCatch(
                () -> {
                    syncExecutor.executeIncrement(userIgn, count);
                    return null;
                },
                dbEx -> {
                    likeBufferStorage.getCounter(userIgn).addAndGet(count);
                    log.error("‼️ [Critical] Redis/DB 동시 장애. 로컬 롤백 완료.");
                    return null;
                },
                TaskContext.of("LikeSync", "RedisFailureRecovery", userIgn)
        );
    }

    private boolean syncWithRetry(String userIgn, long count) {
        return executor.executeOrDefault(() -> {
            Retry.decorateRunnable(likeSyncRetry, () ->
                    syncExecutor.executeIncrement(userIgn, count)
            ).run();
            return true;
        }, false, TaskContext.of("LikeSync", "DbSyncWithRetry", userIgn));
    }
}
