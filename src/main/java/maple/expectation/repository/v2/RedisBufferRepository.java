package maple.expectation.repository.v2;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisBufferRepository {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String GLOBAL_PENDING_KEY = "buffer:likes:total_count";

    /**
     * ✅ 전역 카운터 증가 (로컬 버퍼에 쌓일 때 호출)
     */
    public void incrementGlobalCount(long delta) {
        redisTemplate.opsForValue().increment(GLOBAL_PENDING_KEY, delta);
    }

    /**
     * ✅ 전역 카운터 감소 (DB로 Flush 완료 시 호출)
     */
    public void decrementGlobalCount(long delta) {
        redisTemplate.opsForValue().decrement(GLOBAL_PENDING_KEY, delta);
    }

    /**
     * ✅ 전역 카운터 조회 (모니터링 서비스에서 호출)
     */
    public long getTotalPendingCount() {
        Object count = redisTemplate.opsForValue().get(GLOBAL_PENDING_KEY);
        if (count == null) return 0L;
        return Long.parseLong(count.toString());
    }
}