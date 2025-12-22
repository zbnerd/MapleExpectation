package maple.expectation.service.v2.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import maple.expectation.aop.annotation.TraceLog;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

//@TraceLog
@Component
public class LikeBufferStorage {

    private final Cache<String, AtomicLong> likeCache;

    public LikeBufferStorage(MeterRegistry registry) { // MeterRegistry 주입
        this.likeCache = Caffeine.newBuilder()
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build();

        // 📊 Custom Gauge 등록: 현재 모든 유저의 버퍼에 쌓인 좋아요 총합
        Gauge.builder("like.buffer.total_pending", this, storage ->
                        storage.getCache().asMap().values().stream()
                                .mapToLong(AtomicLong::get)
                                .sum()
                )
                .description("DB에 반영되기 전 메모리에 대기 중인 좋아요 총합")
                .register(registry);
    }

    public AtomicLong getCounter(String userIgn) {
        return likeCache.get(userIgn, key -> new AtomicLong(0));
    }

    public Cache<String, AtomicLong> getCache() {
        return likeCache;
    }
}