package maple.expectation.global.cache;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import maple.expectation.global.executor.LogicExecutor;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.AbstractCacheManager;

import java.util.Collection;
import java.util.List;

/**
 * 2계층 캐시 매니저 (L1: Caffeine, L2: Redis)
 *
 * <h4>Issue #148: TieredCache에 분산 락 및 메트릭 지원 추가</h4>
 * <ul>
 *   <li>RedissonClient: 분산 락 기반 Single-flight 패턴</li>
 *   <li>MeterRegistry: 캐시 히트/미스 메트릭 수집</li>
 * </ul>
 */
@RequiredArgsConstructor
public class TieredCacheManager extends AbstractCacheManager {
    private final CacheManager l1Manager;
    private final CacheManager l2Manager;
    private final LogicExecutor executor;
    private final RedissonClient redissonClient; // Issue #148: 분산 락용
    private final MeterRegistry meterRegistry;   // Issue #148: 메트릭 수집용

    @Override
    protected Collection<? extends Cache> loadCaches() {
        return List.of();
    }

    @Override
    public Cache getCache(String name) {
        Cache l1 = l1Manager.getCache(name);
        Cache l2 = l2Manager.getCache(name);

        // Issue #148: TieredCache에 RedissonClient, MeterRegistry 전달
        return new TieredCache(l1, l2, executor, redissonClient, meterRegistry);
    }
}
