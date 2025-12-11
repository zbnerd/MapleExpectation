package maple.expectation.service.v2.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import maple.expectation.aop.annotation.TraceLog;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

//@TraceLog
@Component
public class LikeBufferStorage {

    // Key: userIgn, Value: 쌓여있는 좋아요 수 (AtomicLong)
    private final Cache<String, AtomicLong> likeCache;

    public LikeBufferStorage() {
        this.likeCache = Caffeine.newBuilder()
                .expireAfterAccess(10, TimeUnit.MINUTES) // 10분간 활동 없으면 제거 (메모리 관리)
                .build();
    }

    public AtomicLong getCounter(String userIgn) {
        // 키가 없으면 0으로 초기화된 AtomicLong 생성 후 반환
        return likeCache.get(userIgn, key -> new AtomicLong(0));
    }

    // 스케줄러가 사용할 맵 반환
    public Cache<String, AtomicLong> getCache() {
        return likeCache;
    }
}