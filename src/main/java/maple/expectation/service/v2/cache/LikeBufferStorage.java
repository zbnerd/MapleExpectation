package maple.expectation.service.v2.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-Memory 좋아요 카운터 버퍼 (Caffeine 기반)
 *
 * <h3>V5 Stateless 전환</h3>
 * <p>이 구현체는 단일 인스턴스 환경용입니다.
 * Scale-out 환경에서는 {@code app.buffer.redis.enabled=true} 설정으로
 * Redis 기반 구현체를 사용하세요.</p>
 *
 * <h3>제약사항</h3>
 * <ul>
 *   <li>인스턴스별 독립 버퍼 → Scale-out 시 데이터 분산</li>
 *   <li>인스턴스 장애 시 버퍼 데이터 유실</li>
 * </ul>
 *
 * @see LikeBufferStrategy 전략 인터페이스
 */
@Slf4j
@Component
public class LikeBufferStorage implements LikeBufferStrategy {

    private final Cache<String, AtomicLong> likeCache;

    public LikeBufferStorage(
            MeterRegistry registry,
            @Value("${like.buffer.local.max-size:10000}") int maxSize) {
        this.likeCache = Caffeine.newBuilder()
                .expireAfterAccess(1, TimeUnit.MINUTES)
                .maximumSize(maxSize)
                .build();

        Gauge.builder("like.buffer.local_pending", this,
                        storage -> likeCache.asMap().values().stream().mapToLong(AtomicLong::get).sum())
                .description("현재 인스턴스의 미반영 좋아요 총합")
                .register(registry);
    }

    @Override
    public Long increment(String userIgn, long delta) {
        AtomicLong counter = getCounter(userIgn);
        return counter.addAndGet(delta);
    }

    @Override
    public Long get(String userIgn) {
        AtomicLong counter = likeCache.getIfPresent(userIgn);
        return counter != null ? counter.get() : 0L;
    }

    @Override
    public Map<String, Long> getAllCounters() {
        Map<String, Long> result = new HashMap<>();
        likeCache.asMap().forEach((key, value) -> result.put(key, value.get()));
        return result;
    }

    @Override
    public Map<String, Long> fetchAndClear(int limit) {
        Map<String, Long> result = new HashMap<>();
        int count = 0;

        for (Map.Entry<String, AtomicLong> entry : likeCache.asMap().entrySet()) {
            if (count >= limit) break;

            long value = entry.getValue().getAndSet(0);
            if (value != 0) {
                result.put(entry.getKey(), value);
                count++;
            }
        }

        return result;
    }

    @Override
    public int getBufferSize() {
        return (int) likeCache.estimatedSize();
    }

    @Override
    public StrategyType getType() {
        return StrategyType.IN_MEMORY;
    }

    /**
     * 카운터 조회 (없으면 생성)
     *
     * <p>내부적으로 increment()에서 사용됩니다.
     * 테스트에서 직접 AtomicLong 접근이 필요한 경우에도 사용됩니다.</p>
     */
    public AtomicLong getCounter(String userIgn) {
        return likeCache.get(userIgn, key -> new AtomicLong(0));
    }

    /**
     * 내부 캐시 접근 (테스트 초기화용)
     *
     * <p>테스트에서 {@code getCache().invalidateAll()} 등으로 초기화할 때 사용됩니다.</p>
     */
    public Cache<String, AtomicLong> getCache() {
        return likeCache;
    }
}
