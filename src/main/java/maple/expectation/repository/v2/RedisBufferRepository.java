package maple.expectation.repository.v2;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisBufferRepository {

    private final StringRedisTemplate redisTemplate;
    private static final String GLOBAL_PENDING_KEY = "buffer:likes:total_count";

    /**
     * ✅ 전역 카운터 증가 (L1 -> L2 전송 시 호출)
     */
    public void incrementGlobalCount(long delta) {
        redisTemplate.opsForValue().increment(GLOBAL_PENDING_KEY, delta);
    }

    /**
     * ✅ [추가됨] 전역 카운터 감소 (L2 -> L3 동기화 성공 시 호출)
     */
    public void decrementGlobalCount(long delta) {
        // 성공적으로 DB에 반영된 수량만큼 전역 카운터에서 차감합니다.
        redisTemplate.opsForValue().decrement(GLOBAL_PENDING_KEY, delta);
    }

    /**
     * ✅ 전역 카운터 조회 (모니터링용)
     */
    public long getTotalPendingCount() {
        String count = redisTemplate.opsForValue().get(GLOBAL_PENDING_KEY);
        return (count == null) ? 0L : Long.parseLong(count);
    }

    /**
     * 💡 참고: 만약 rename 전략이 아닌 getAndSet 전략을 쓴다면 아래 메서드를 사용합니다.
     */
    public long getAndClearGlobalCount() {
        String count = redisTemplate.opsForValue().getAndSet(GLOBAL_PENDING_KEY, "0");
        return (count == null) ? 0L : Long.parseLong(count);
    }
}