package maple.expectation.service.v2.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class LikeBufferStorage {

    private final Cache<String, AtomicLong> likeCache;

    public LikeBufferStorage(MeterRegistry registry) {
        this.likeCache = Caffeine.newBuilder()
                .expireAfterAccess(1, TimeUnit.MINUTES)
                .maximumSize(1000) // 메모리 보호를 위해 사이즈 제한 추가
                .build();
        Gauge.builder("like.buffer.local_pending", this,
                        storage -> likeCache.asMap().values().stream().mapToLong(AtomicLong::get).sum())
                .description("현재 인스턴스의 미반영 좋아요 총합")
                .register(registry);
    }

    public AtomicLong getCounter(String userIgn) {
        return likeCache.get(userIgn, key -> new AtomicLong(0));
    }

    public Cache<String, AtomicLong> getCache() {
        return likeCache;
    }
}