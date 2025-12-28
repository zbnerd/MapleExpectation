package maple.expectation.global.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.AbstractCacheManager;
import java.util.Collection;
import java.util.List;

@RequiredArgsConstructor
public class TieredCacheManager extends AbstractCacheManager {
    private final CacheManager l1Manager;
    private final CacheManager l2Manager;

    @Override
    protected Collection<? extends Cache> loadCaches() { return List.of(); }

    @Override
    public Cache getCache(String name) {
        Cache l1 = l1Manager.getCache(name);
        Cache l2 = l2Manager.getCache(name);
        // 모든 캐시 객체에 레포지토리 전달 (캐시 이름으로 내부에서 선별 사용)
        return new TieredCache(l1, l2);
    }
}