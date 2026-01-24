package maple.expectation.global.cache;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.AbstractCacheManager;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 2계층 캐시 매니저 (L1: Caffeine, L2: Redis)
 *
 * <h4>Issue #148: TieredCache에 분산 락 및 메트릭 지원 추가</h4>
 * <ul>
 *   <li>RedissonClient: 분산 락 기반 Single-flight 패턴</li>
 *   <li>MeterRegistry: 캐시 히트/미스 메트릭 수집</li>
 * </ul>
 *
 * <h4>P2 Performance Fix: 인스턴스 풀링</h4>
 * <ul>
 *   <li><b>문제</b>: getCache() 호출마다 새 TieredCache 인스턴스 생성</li>
 *   <li><b>해결</b>: ConcurrentHashMap으로 인스턴스 캐싱 (O(1) 조회)</li>
 *   <li><b>Green Agent 피드백 반영</b></li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class TieredCacheManager extends AbstractCacheManager {
    private final CacheManager l1Manager;
    private final CacheManager l2Manager;
    private final LogicExecutor executor;
    private final RedissonClient redissonClient; // Issue #148: 분산 락용
    /**
     * -- GETTER --
     *  메트릭 레지스트리 접근자 (#264 Fast Path 메트릭용)
     */
    @Getter
    private final MeterRegistry meterRegistry;   // Issue #148: 메트릭 수집용

    /**
     * P2 FIX: TieredCache 인스턴스 풀 (동일 이름 캐시는 한 번만 생성)
     */
    private final ConcurrentMap<String, Cache> cachePool = new ConcurrentHashMap<>();

    @Override
    protected Collection<? extends Cache> loadCaches() {
        return List.of();
    }

    /**
     * 캐시 인스턴스 조회 (인스턴스 풀링 적용)
     *
     * <p><b>P2 Performance Fix:</b> ConcurrentHashMap.computeIfAbsent()로 O(1) 조회</p>
     * <p><b>스레드 안전성:</b> ConcurrentHashMap의 원자적 연산으로 동시성 보장</p>
     *
     * @param name 캐시 이름
     * @return TieredCache 인스턴스 (재사용)
     */
    @Override
    public Cache getCache(String name) {
        return cachePool.computeIfAbsent(name, this::createTieredCache);
    }

    /**
     * TieredCache 인스턴스 생성 (최초 1회만 호출됨)
     */
    private Cache createTieredCache(String name) {
        Cache l1 = l1Manager.getCache(name);
        Cache l2 = l2Manager.getCache(name);

        log.debug("[TieredCacheManager] Creating TieredCache instance: name={}", name);

        // Issue #148: TieredCache에 RedissonClient, MeterRegistry 전달
        return new TieredCache(l1, l2, executor, redissonClient, meterRegistry);
    }

    /**
     * L1 캐시 직접 접근 (Fast Path용) (#264)
     *
     * <h4>Issue #264: 캐시 히트 성능 최적화</h4>
     * <ul>
     *   <li>L1(Caffeine) 캐시에서 직접 조회</li>
     *   <li>TieredCache/LogicExecutor 오버헤드 우회</li>
     *   <li>Executor 스레드 풀 경합 방지</li>
     * </ul>
     *
     * <h4>Context7 Best Practice: Caffeine getIfPresent()</h4>
     * <p>값이 있으면 즉시 반환, 없으면 null (loader 실행 X)</p>
     *
     * <h4>5-Agent Council 합의</h4>
     * <ul>
     *   <li>🟢 Green: 캐시 히트 시 RPS 3-5배 향상 기대</li>
     *   <li>🔵 Blue: OCP 준수 - 기존 코드 수정 없음</li>
     *   <li>🔴 Red: Graceful Degradation - L1 미스 시 기존 경로로 폴백</li>
     * </ul>
     *
     * @param name 캐시 이름
     * @return L1 캐시 인스턴스 (Caffeine) - null 가능 (캐시 미등록 시)
     */
    public Cache getL1CacheDirect(String name) {
        return l1Manager.getCache(name);
    }

}
