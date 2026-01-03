package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
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
    private static final String REDIS_HASH_KEY = "buffer:likes";

    /**
     * ✅ L1(Caffeine) -> L2(Redis) 전송
     */
    public void flushLocalToRedis() {
        Map<String, AtomicLong> snapshot = likeBufferStorage.getCache().asMap();
        if (snapshot.isEmpty()) return;
        snapshot.forEach(this::processLocalBufferEntry);
    }

    /**
     * ✅ L1(Caffeine) -> L2(Redis) 전송 (Graceful Shutdown용)
     * <p>
     * Redis 장애 시 로컬 파일로 백업하여 데이터 유실을 방지합니다.
     * {@link FlushResult}를 반환하여 성공/실패 건수를 추적합니다.
     *
     * @return FlushResult (Redis 성공 건수, 파일 백업 건수)
     */
    public FlushResult flushLocalToRedisWithFallback() {
        Map<String, AtomicLong> snapshot = likeBufferStorage.getCache().asMap();
        if (snapshot.isEmpty()) return FlushResult.empty();

        AtomicInteger redisSuccessCount = new AtomicInteger(0);
        AtomicInteger fileBackupCount = new AtomicInteger(0);

        snapshot.forEach((userIgn, atomicCount) -> {
            long count = atomicCount.getAndSet(0);
            if (count <= 0) return;
            try {
                redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, count);
                redisBufferRepository.incrementGlobalCount(count);
                redisSuccessCount.incrementAndGet();
            } catch (Exception e) {
                log.warn("⚠️ [Shutdown Flush] Redis 전송 실패, 파일 백업: {} ({}건)", userIgn, count);
                shutdownDataPersistenceService.appendLikeEntry(userIgn, count);
                fileBackupCount.incrementAndGet();
            }
        });

        return new FlushResult(redisSuccessCount.get(), fileBackupCount.get());
    }

    @ObservedTransaction("scheduler.like.redis_to_db")
    public void syncRedisToDatabase() {
        String tempKey = REDIS_HASH_KEY + ":sync:" + UUID.randomUUID();
        try {
            Boolean hasKey = redisTemplate.hasKey(REDIS_HASH_KEY);
            if (!hasKey) return;

            redisTemplate.rename(REDIS_HASH_KEY, tempKey);

            Map<Object, Object> entries = redisTemplate.opsForHash().entries(tempKey);
            if (entries.isEmpty()) return;

            AtomicLong actualSuccessTotal = new AtomicLong(0);

            entries.forEach((key, value) -> {
                String userIgn = (String) key;
                long count = Long.parseLong((String) value);

                if (syncWithRetry(userIgn, count)) {
                    actualSuccessTotal.addAndGet(count);
                } else {
                    // 개별 항목 실패 시 원본 키로 즉시 복구
                    redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, count);
                    log.warn("♻️ [Sync Recovery] DB 반영 실패로 데이터 Redis 복구: {} ({}건)", userIgn, count);
                }
            });

            long totalToDecrement = actualSuccessTotal.get();
            if (totalToDecrement > 0) {
                redisBufferRepository.decrementGlobalCount(totalToDecrement);
                log.info("✅ [Sync Success] 총 {}건의 좋아요가 DB에 반영되었습니다.", totalToDecrement);
            }
            redisTemplate.delete(tempKey);

        } catch (Exception e) {
            log.error("⚠️ [Sync Logic Error] 동기화 프로세스 중 치명적 오류: {}", e.getMessage());

            // 🚀 [이슈 #123] 롤백 로직: 임시 키에 데이터가 남아있다면 원본 키로 병합
            try {
                if (redisTemplate.hasKey(tempKey)) {
                    Map<Object, Object> strandedEntries = redisTemplate.opsForHash().entries(tempKey);
                    strandedEntries.forEach((key, value) ->
                            redisTemplate.opsForHash().increment(REDIS_HASH_KEY, (String) key, Long.parseLong((String) value))
                    );
                    redisTemplate.delete(tempKey);
                    log.info("♻️ [Sync Rollback] 임시 키 데이터를 원본 버퍼로 병합 완료");
                }
            } catch (Exception rollbackEx) {
                log.error("❌ [Critical] Sync 롤백 중 추가 장애 발생: {}", rollbackEx.getMessage());
            }
        }
    }

    private void processLocalBufferEntry(String userIgn, AtomicLong atomicCount) {
        long count = atomicCount.getAndSet(0);
        if (count <= 0) return;
        try {
            redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, count);
            redisBufferRepository.incrementGlobalCount(count);
        } catch (Exception e) {
            handleRedisFailure(userIgn, count, e);
        }
    }

    private void handleRedisFailure(String userIgn, long count, Exception e) {
        log.error("🚑 [Redis Down] L2 전송 실패. DB 직접 반영 시도: {}", userIgn);
        try {
            syncExecutor.executeIncrement(userIgn, count);
        } catch (Exception dbEx) {
            likeBufferStorage.getCounter(userIgn).addAndGet(count);
            log.error("‼️ [Critical] Redis/DB 동시 장애. 로컬 롤백 완료.");
        }
    }

    private boolean syncWithRetry(String userIgn, long count) {
        try {
            Retry.decorateRunnable(likeSyncRetry, () -> {
                syncExecutor.executeIncrement(userIgn, count);
            }).run();
            return true;
        } catch (Exception e) {
            log.error("❌ [L2->L3 Sync] 재시도 후 최종 실패: {}", userIgn);
            return false;
        }
    }
}