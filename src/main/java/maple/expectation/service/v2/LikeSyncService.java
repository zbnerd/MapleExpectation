package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.service.v2.like.event.LikeSyncFailedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class LikeSyncService {

    private final LikeBufferStorage likeBufferStorage;
    private final LikeSyncExecutor syncExecutor;
    private final StringRedisTemplate redisTemplate;
    private final Retry likeSyncRetry;

    private static final String REDIS_HASH_KEY = "buffer:likes";

    public void flushLocalToRedis() {
        Map<String, AtomicLong> snapshot = likeBufferStorage.getCache().asMap();
        if (snapshot.isEmpty()) return;
        snapshot.forEach(this::processLocalBufferEntry);
    }

    @ObservedTransaction("scheduler.like.redis_to_db")
    public void syncRedisToDatabase() {
        Map<Object, Object> entries = fetchRedisEntries();
        if (entries.isEmpty()) return;
        entries.forEach((key, value) -> syncWithRetry((String) key, Long.parseLong((String) value)));
    }

    private void processLocalBufferEntry(String userIgn, AtomicLong atomicCount) {
        long count = atomicCount.getAndSet(0);
        if (count <= 0) return;
        try {
            redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, count);
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

    private Map<Object, Object> fetchRedisEntries() {
        try { return redisTemplate.opsForHash().entries(REDIS_HASH_KEY); }
        catch (Exception e) { return Map.of(); }
    }

    private void syncWithRetry(String userIgn, long count) {
        try {
            Retry.decorateRunnable(likeSyncRetry, () -> {
                syncExecutor.executeIncrement(userIgn, count);
                redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, -count);
            }).run();
        } catch (Exception e) {
            log.error("❌ [L2->L3 Sync] 최종 실패: {}", userIgn);
        }
    }
}