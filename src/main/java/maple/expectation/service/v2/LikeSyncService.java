package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.shutdown.dto.FlushResult;
import maple.expectation.repository.v2.RedisBufferRepository;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class LikeSyncService {

    private final LikeBufferStorage likeBufferStorage;
    private final LikeSyncExecutor syncExecutor;
    private final StringRedisTemplate redisTemplate;
    private final RedisBufferRepository redisBufferRepository;
    private final Retry likeSyncRetry;
    private final ShutdownDataPersistenceService shutdownDataPersistenceService;
    private final LogicExecutor executor; // ✅ 지능형 실행 엔진 주입

    private static final String REDIS_HASH_KEY = "buffer:likes";

    /**
     * ✅ L1 -> L2 전송
     */
    public void flushLocalToRedis() {
        Map<String, AtomicLong> snapshot = likeBufferStorage.getCache().asMap();
        if (snapshot.isEmpty()) return;
        snapshot.forEach(this::processLocalBufferEntry);
    }

    /**
     * ✅ Graceful Shutdown용 전송 (평탄화 완료)
     */
    public FlushResult flushLocalToRedisWithFallback() {
        Map<String, AtomicLong> snapshot = likeBufferStorage.getCache().asMap();
        if (snapshot.isEmpty()) return FlushResult.empty();

        AtomicInteger redisSuccessCount = new AtomicInteger(0);
        AtomicInteger fileBackupCount = new AtomicInteger(0);

        snapshot.forEach((userIgn, atomicCount) -> {
            long count = atomicCount.getAndSet(0);
            if (count <= 0) return;

            // [패턴 5] executeWithRecovery: Redis 실패 시 파일 백업 로직으로 자동 복구
            executor.executeWithRecovery(
                    () -> {
                        redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, count);
                        redisBufferRepository.incrementGlobalCount(count);
                        redisSuccessCount.incrementAndGet();
                        return null;
                    },
                    (e) -> {
                        log.warn("⚠️ [Shutdown Flush] Redis 전송 실패, 파일 백업: {} ({}건)", userIgn, count);
                        shutdownDataPersistenceService.appendLikeEntry(userIgn, count);
                        fileBackupCount.incrementAndGet();
                        return null;
                    },
                    TaskContext.of("LikeSync", "ShutdownFlush", userIgn)
            );
        });

        return new FlushResult(redisSuccessCount.get(), fileBackupCount.get());
    }

    /**
     * ✅ Redis -> DB 동기화 (트랜잭션 롤백 로직 평탄화)
     */
    @ObservedTransaction("scheduler.like.redis_to_db")
    public void syncRedisToDatabase() {
        String tempKey = REDIS_HASH_KEY + ":sync:" + UUID.randomUUID();
        TaskContext context = TaskContext.of("LikeSync", "RedisToDb", tempKey);

        // [패턴 1] executeWithFinally: 성공/실패 여부와 상관없이 임시 키 자원 해제(Cleanup) 보장
        executor.executeWithFinally(
                () -> {
                    doSyncProcess(tempKey);
                    return null;
                },
                () -> cleanupTempKey(tempKey), // 에러 발생 시 데이터 복구 및 키 삭제 전담
                context
        );
    }

    private void doSyncProcess(String tempKey) {
        if (Boolean.FALSE.equals(redisTemplate.hasKey(REDIS_HASH_KEY))) return;

        // opsForHash는 한 번만 획득 (테스트에서도 mock 1개로 잡힘)
        var ops = redisTemplate.opsForHash();

        redisTemplate.rename(REDIS_HASH_KEY, tempKey);

        Map<Object, Object> entries = ops.entries(tempKey);
        if (entries.isEmpty()) {
            // rename으로 만들어진 tempKey는 정리하고 종료 (cleanup skip 유도)
            redisTemplate.delete(tempKey);
            return;
        }

        long successTotal = 0L;
        boolean needsCleanup = false;

        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String userIgn = (String) entry.getKey();
            long count = parseToLong(entry.getValue());

            boolean success;
            try {
                success = syncWithRetry(userIgn, count);
            } catch (Throwable t) {
                // syncWithRetry 내부가 executor 기반이지만, 혹시라도 예외가 새어나오면 "실패"로 취급
                log.warn("⚠️ [Sync] 예상치 못한 예외로 실패 처리: {} ({}건)", userIgn, count, t);
                success = false;
            }

            if (success) {
                successTotal += count;

                // ✅ 성공 엔트리는 tempKey에서 제거 (HDEL)
                // (예외가 나더라도 전체 플로우를 끊지 않음: 끝까지 가서 tempKey를 삭제해야 중복 위험이 줄어듦)
                try {
                    ops.delete(tempKey, userIgn);
                } catch (Exception e) {
                    log.warn("⚠️ [Sync] HDEL 실패(성공 엔트리): {} ({}건) - tempKey delete로 수습 예정", userIgn, count, e);
                }
                continue;
            }

            // 실패 엔트리: 즉시 원본 버퍼로 복구 + tempKey에서 제거(HDEL)
            boolean restored = false;
            try {
                ops.increment(REDIS_HASH_KEY, userIgn, count);
                restored = true;
                log.warn("♻️ [Sync Recovery] DB 반영 실패로 Redis 복구: {} ({}건)", userIgn, count);
            } catch (Exception restoreEx) {
                // 복구 실패면 tempKey를 남겨 cleanupTempKey에서 재시도하게 함
                needsCleanup = true;
                log.error("‼️ [Sync Recovery] 원본 버퍼 복구 실패: {} ({}건) - cleanup에서 재시도", userIgn, count, restoreEx);
            }

            if (restored) {
                try {
                    ops.delete(tempKey, userIgn);
                } catch (Exception e) {
                    // HDEL이 실패하면, cleanup에서 중복 복구 위험이 생길 수 있어 경고만 남김
                    // (대부분은 마지막 tempKey delete로 수습됨)
                    log.warn("⚠️ [Sync] HDEL 실패(복구된 엔트리): {} ({}건)", userIgn, count, e);
                }
            }
        }

        // 성공 누적분만 globalCount 차감
        if (successTotal > 0) {
            redisBufferRepository.decrementGlobalCount(successTotal);
        }

        // ✅ 정상 케이스(복구 실패 없음): tempKey 삭제 → cleanupTempKey는 skip
        // ✅ 복구가 필요한 케이스(needsCleanup=true): tempKey 유지 → cleanupTempKey가 잔여분 복구
        if (!needsCleanup) {
            redisTemplate.delete(tempKey);
        }
    }

    private long parseToLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    /**
     * 🚀 [이슈 #123] 자원 정리 및 롤백 로직 격리
     * - tempKey가 남아있을 때만 실행(= doSyncProcess에서 완전 정리 못한 경우)
     */
    private void cleanupTempKey(String tempKey) {
        executor.executeVoid(() -> {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(tempKey))) {
                var ops = redisTemplate.opsForHash();

                Map<Object, Object> strandedEntries = ops.entries(tempKey);
                strandedEntries.forEach((key, value) -> {
                    String userIgn = (String) key;
                    long count = parseToLong(value);
                    ops.increment(REDIS_HASH_KEY, userIgn, count);
                });

                redisTemplate.delete(tempKey);
                log.info("♻️ [Sync Cleanup] 임시 키 데이터를 원본 버퍼로 병합 완료");
            }
        }, TaskContext.of("LikeSync", "Cleanup", tempKey));
    }

    private void processLocalBufferEntry(String userIgn, AtomicLong atomicCount) {
        long count = atomicCount.getAndSet(0);
        if (count <= 0) return;

        executor.executeWithRecovery(
                () -> {
                    redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, count);
                    redisBufferRepository.incrementGlobalCount(count);
                    return null;
                },
                (e) -> {
                    handleRedisFailure(userIgn, count, e);
                    return null;
                },
                TaskContext.of("LikeSync", "L1toL2", userIgn)
        );
    }

    private void handleRedisFailure(String userIgn, long count, Throwable e) {
        log.error("🚑 [Redis Down] L2 전송 실패. DB 직접 반영 시도: {}", userIgn);
        executor.executeWithRecovery(
                () -> {
                    syncExecutor.executeIncrement(userIgn, count);
                    return null;
                },
                (dbEx) -> {
                    likeBufferStorage.getCounter(userIgn).addAndGet(count);
                    log.error("‼️ [Critical] Redis/DB 동시 장애. 로컬 롤백 완료.");
                    return null;
                },
                TaskContext.of("LikeSync", "RedisFailureRecovery", userIgn)
        );
    }

    private boolean syncWithRetry(String userIgn, long count) {
        // Retry 로직도 execute를 통해 관측성 확보 가능
        return executor.executeOrDefault(() -> {
            Retry.decorateRunnable(likeSyncRetry, () ->
                    syncExecutor.executeIncrement(userIgn, count)
            ).run();
            return true;
        }, false, TaskContext.of("LikeSync", "DbSyncWithRetry", userIgn));
    }

}