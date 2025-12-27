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
    private final ApplicationEventPublisher eventPublisher;
    private final Retry likeSyncRetry;
    private final StringRedisTemplate redisTemplate;

    private static final String REDIS_HASH_KEY = "buffer:likes";

    /**
     * 🚀 [MISSION 1] 로컬(L1) -> Redis(L2) 데이터 전송
     * Redis 장애 시 DB(L3)로 직접 Fallback 합니다.
     */
    public void flushLocalToRedis() {
        Map<String, AtomicLong> snapshot = likeBufferStorage.getCache().asMap();
        if (snapshot.isEmpty()) return;

        // 💡 람다 내부를 최대한 단순하게 유지하기 위해 별도 메서드로 추출
        snapshot.forEach(this::processLocalBufferEntry);
    }

    /**
     * 🚀 [MISSION 2] Redis(L2) -> DB(L3) 최종 동기화
     */
    @ObservedTransaction("scheduler.like.redis_to_db")
    public void syncRedisToDatabase() {
        Map<Object, Object> entries = fetchRedisEntries();
        if (entries.isEmpty()) return;

        log.info("📊 [Global Sync] Redis로부터 {}건의 데이터를 처리합니다.", entries.size());
        entries.forEach((key, value) ->
                syncWithRetry((String) key, Long.parseLong((String) value))
        );
    }

    // --- Private Helper Methods (가독성 및 괄호 지옥 해결) ---

    private void processLocalBufferEntry(String userIgn, AtomicLong atomicCount) {
        long count = atomicCount.getAndSet(0);
        if (count <= 0) return;

        try {
            // 1. 정상 시나리오: Redis(L2)에 적재
            redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, count);
        } catch (Exception e) {
            // 2. Redis 장애 시나리오: 즉시 DB(L3) 반영 시도 (Fallback)
            handleRedisFailure(userIgn, count, e);
        }
    }

    private void handleRedisFailure(String userIgn, long count, Exception e) {
        log.error("🚑 [Redis Down] L2 전송 실패. DB 직접 반영을 시도합니다: {}", userIgn);
        try {
            // Redis가 죽었으므로 바로 DB로 반영
            syncExecutor.executeIncrement(userIgn, count);
        } catch (Exception dbEx) {
            // 3. DB까지 장애 시: 로컬 버퍼로 복구 (최후의 보루)
            likeBufferStorage.getCounter(userIgn).addAndGet(count);
            log.error("‼️ [Critical] Redis/DB 동시 장애 발생. 데이터를 로컬로 롤백합니다.");
        }
    }

    private Map<Object, Object> fetchRedisEntries() {
        try {
            return redisTemplate.opsForHash().entries(REDIS_HASH_KEY);
        } catch (Exception e) {
            log.warn("⏭️ Redis 연결 불가로 L2->L3 동기화를 스킵합니다.");
            return Map.of();
        }
    }

    private void syncWithRetry(String userIgn, long count) {
        try {
            Retry.decorateRunnable(likeSyncRetry, () -> {
                syncExecutor.executeIncrement(userIgn, count);
                // DB 성공 시 Redis 수치 차감
                redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, -count);
            }).run();
        } catch (Exception e) {
            log.error("❌ [L2->L3 Sync] 최종 실패: {} (건수: {})", userIgn, count);
            eventPublisher.publishEvent(new LikeSyncFailedEvent(userIgn, count, 3, e));
        }
    }
}