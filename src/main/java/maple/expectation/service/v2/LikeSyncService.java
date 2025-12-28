package maple.expectation.service.v2;

import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.repository.v2.RedisBufferRepository;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
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
     * 💡 [Issue #28 해결] L2(Redis) -> L3(DB) 최종 동기화
     * 원자적 Rename 전략과 부분 성공 집계 로직을 적용했습니다.
     */
    @ObservedTransaction("scheduler.like.redis_to_db")
    public void syncRedisToDatabase() {
        // 1. 작업 격리를 위한 임시 키 생성 (UUID 활용)
        String tempKey = REDIS_HASH_KEY + ":sync:" + UUID.randomUUID();

        try {
            // 2. 처리할 데이터가 있는지 확인
            Boolean hasKey = redisTemplate.hasKey(REDIS_HASH_KEY);
            if (Boolean.FALSE.equals(hasKey)) return;

            // 3. [Atomic Rename] 원본 버퍼를 나만 아는 임시 키로 이동
            redisTemplate.rename(REDIS_HASH_KEY, tempKey);

            Map<Object, Object> entries = redisTemplate.opsForHash().entries(tempKey);
            if (entries.isEmpty()) return;

            // ✅ 4. 실제 성공한 총 수량을 추적 (AtomicLong 사용)
            AtomicLong actualSuccessTotal = new AtomicLong(0);

            entries.forEach((key, value) -> {
                String userIgn = (String) key;
                long count = Long.parseLong((String) value);

                // ✅ 5. 개별 데이터 반영 시도 및 결과 확인
                if (syncWithRetry(userIgn, count)) {
                    actualSuccessTotal.addAndGet(count);
                } else {
                    // ❌ 6. 실패 시 데이터 유실 방지를 위해 원본 버퍼(REDIS_HASH_KEY)로 복구
                    redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, count);
                    log.warn("♻️ [Sync Recovery] DB 반영 실패로 데이터 Redis 복구: {} ({}건)", userIgn, count);
                }
            });

            // ✅ 7. 실제로 성공한 수량만큼만 전역 카운터에서 차감 (1704 문제 해결)
            long totalToDecrement = actualSuccessTotal.get();
            if (totalToDecrement > 0) {
                redisBufferRepository.decrementGlobalCount(totalToDecrement);
                log.info("✅ [Sync Success] 총 {}건의 좋아요가 DB에 반영되었습니다.", totalToDecrement);
            }

            // 8. 처리가 끝난 임시 키 삭제
            redisTemplate.delete(tempKey);

        } catch (Exception e) {
            log.error("⚠️ [Sync Logic Error] 동기화 프로세스 중 치명적 오류: {}", e.getMessage());
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

    /**
     * 💡 리턴 타입을 boolean으로 변경하여 성공 여부를 반환합니다.
     */
    private boolean syncWithRetry(String userIgn, long count) {
        try {
            Retry.decorateRunnable(likeSyncRetry, () -> {
                syncExecutor.executeIncrement(userIgn, count);
            }).run();
            return true; // 성공 시 true
        } catch (Exception e) {
            log.error("❌ [L2->L3 Sync] 재시도 후 최종 실패: {}", userIgn);
            return false; // 실패 시 false
        }
    }
}