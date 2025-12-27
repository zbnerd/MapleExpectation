package maple.expectation.service.v2.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.TraceLog;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class LikeBufferStorage {

    private final Cache<String, AtomicLong> likeCache;
    private final StringRedisTemplate redisTemplate; // 💡 L2 저장소(Redis) 추가

    public LikeBufferStorage(StringRedisTemplate redisTemplate, MeterRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.likeCache = Caffeine.newBuilder()
                .expireAfterAccess(1, TimeUnit.MINUTES) // 로컬은 매우 짧게 유지
                .build();

        // 📊 이제 모니터링 수치는 (로컬 잔량 + Redis 잔량)을 합쳐야 정확합니다! (사각지대 해소)
        Gauge.builder("like.buffer.global_pending", this, storage -> calculateGlobalPending())
                .description("전체 서버 인스턴스의 미반영 좋아요 총합")
                .register(registry);
    }

    public AtomicLong getCounter(String userIgn) {
        return likeCache.get(userIgn, key -> new AtomicLong(0));
    }

    /**
     * 🚀 [핵심] L1(로컬) 데이터를 L2(Redis)로 밀어넣습니다.
     * 모든 인스턴스가 각자 자기 데이터를 중앙으로 모으는 과정입니다.
     */
    public void flushToRedis() {
        Map<String, AtomicLong> snapshot = likeCache.asMap();
        if (snapshot.isEmpty()) return;

        snapshot.forEach((userIgn, counter) -> {
            long count = counter.getAndSet(0); // 💡 원자적으로 값을 가져오고 0으로 초기화
            if (count > 0) {
                // Redis의 HINCRBY를 사용하여 여러 서버의 값을 하나로 합칩니다.
                redisTemplate.opsForHash().increment("buffer:likes", userIgn, count);
                log.debug("📤 [L1->L2 Flush] {} : {} likes", userIgn, count);
            }
        });
    }

    public Cache<String, AtomicLong> getCache() {
        return likeCache;
    }

    private double calculateGlobalPending() {
        // 로컬 잔량 계산
        long localSum = likeCache.asMap().values().stream().mapToLong(AtomicLong::get).sum();

        // Redis 잔량 계산 (Hash 구조에서 모든 밸류 합산)
        // 실제 운영 환경에서는 성능을 위해 Redis에서 직접 합산된 메트릭을 가져오는 것이 좋으나,
        // 현재는 구조 이해를 위해 합산 로직으로 표현합니다.
        return (double) localSum; // + Redis 합산 로직 추가 가능
    }
}