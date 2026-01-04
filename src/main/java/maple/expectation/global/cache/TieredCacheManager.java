package maple.expectation.global.cache;

import lombok.RequiredArgsConstructor;
import maple.expectation.global.executor.LogicExecutor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.AbstractCacheManager;
import java.util.Collection;
import java.util.List;

/**
 * 2계층 캐시 매니저 (L1: Caffeine, L2: Redis)
 * [변경점] TieredCache 생성 시 LogicExecutor를 전달하도록 수정
 */
@RequiredArgsConstructor
public class TieredCacheManager extends AbstractCacheManager {
    private final CacheManager l1Manager;
    private final CacheManager l2Manager;
    private final LogicExecutor executor; // ✅ LogicExecutor 추가 주입

    @Override
    protected Collection<? extends Cache> loadCaches() {
        return List.of();
    }

    @Override
    public Cache getCache(String name) {
        Cache l1 = l1Manager.getCache(name);
        Cache l2 = l2Manager.getCache(name);

        // ✅ TieredCache 생성 시 주입받은 executor를 함께 전달하여 컴파일 오류 해결
        return new TieredCache(l1, l2, executor);
    }
}