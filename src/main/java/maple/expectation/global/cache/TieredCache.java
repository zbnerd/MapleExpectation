package maple.expectation.global.cache;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.cache.invalidation.CacheInvalidationEvent;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 2층 구조 캐시 (L1: Caffeine, L2: Redis)
 *
 * <h4>Issue #148: Race Condition 제거 및 L1/L2 일관성 보장</h4>
 * <ul>
 *   <li>Cache Stampede 방지: Redisson 분산 락 기반 Single-flight 패턴</li>
 *   <li>Layer Consistency: L2 저장 성공 시에만 L1 저장</li>
 *   <li>Watchdog 모드: leaseTime 생략으로 자동 연장</li>
 * </ul>
 *
 * @see <a href="https://github.com/issue/148">Issue #148</a>
 */
@Slf4j
@RequiredArgsConstructor
public class TieredCache implements Cache {
    private final Cache l1; // Caffeine (Local)
    private final Cache l2; // Redis (Distributed)
    private final LogicExecutor executor;
    private final RedissonClient redissonClient; // 분산 락용
    private final MeterRegistry meterRegistry;   // 메트릭 수집용
    private final String instanceId;             // Issue #278: Self-skip용 (P0-2)
    private final Consumer<CacheInvalidationEvent> invalidationCallback; // Issue #278: Cache Coherence (P0-4)

    /** 락 획득 대기 타임아웃 (초) */
    private static final int LOCK_WAIT_SECONDS = 30;

    @Override
    public String getName() { return l2.getName(); }

    @Override
    public Object getNativeCache() { return l2.getNativeCache(); }

    /**
     * 캐시 조회 (L1 → L2 순서, Backfill 포함)
     *
     * <p>CLAUDE.md 섹션 15 준수: 람다 → 메서드 참조</p>
     */
    @Override
    public ValueWrapper get(Object key) {
        TaskContext context = TaskContext.of("Cache", "Get", key.toString());
        return executor.execute(() -> getFromCacheLayers(key), context);
    }

    /**
     * L1 → L2 순차 조회 (Optional 체이닝 - Modern Java 스타일)
     */
    private ValueWrapper getFromCacheLayers(Object key) {
        return Optional.ofNullable(l1.get(key))
                .map(w -> tapCacheHit(w, "L1"))
                .orElseGet(() -> getFromL2WithBackfill(key));
    }

    /**
     * L2 조회 및 L1 Backfill (Optional 체이닝)
     */
    private ValueWrapper getFromL2WithBackfill(Object key) {
        return Optional.ofNullable(l2.get(key))
                .map(w -> {
                    l1.put(key, w.get()); // Backfill
                    return tapCacheHit(w, "L2");
                })
                .orElse(null);
    }

    /**
     * 캐시 저장 (L2 → L1 순서 - 일관성 보장)
     *
     * <p><b>Issue #148:</b> L2 저장 성공 시에만 L1 저장</p>
     */
    @Override
    public void put(Object key, Object value) {
        TaskContext context = TaskContext.of("Cache", "Put", key.toString());

        boolean l2Success = executor.executeOrDefault(
                () -> { l2.put(key, value); return true; },
                false,
                context
        );

        if (l2Success) {
            executor.executeVoid(() -> l1.put(key, value), context);
        } else {
            log.warn("[TieredCache] L2 put failed, skipping L1 for consistency: key={}", key);
            recordL2Failure();
        }
    }

    /**
     * 캐시 키 무효화 (L1 → L2 → Pub/Sub 전파)
     *
     * <h4>Issue #278: L1 Cache Coherence (P0-1)</h4>
     * <p>evict 후 다른 인스턴스의 L1 캐시도 무효화하도록 이벤트 발행</p>
     */
    @Override
    public void evict(Object key) {
        executor.executeVoid(() -> {
            l1.evict(key);
            l2.evict(key);
            publishEvictEvent(key);
        }, TaskContext.of("Cache", "Evict", key.toString()));
    }

    /**
     * 캐시 전체 무효화 (L1 → L2 → Pub/Sub 전파)
     *
     * <h4>Issue #278: L1 Cache Coherence (P0-1)</h4>
     * <p>clear 후 다른 인스턴스의 L1 캐시도 전체 무효화하도록 이벤트 발행</p>
     */
    @Override
    public void clear() {
        executor.executeVoid(() -> {
            l1.clear();
            l2.clear();
            publishClearAllEvent();
        }, TaskContext.of("Cache", "Clear"));
    }

    /**
     * EVICT 이벤트 발행 (Section 15: 메서드 추출)
     */
    private void publishEvictEvent(Object key) {
        invalidationCallback.accept(
                CacheInvalidationEvent.evict(getName(), key.toString(), instanceId)
        );
    }

    /**
     * CLEAR_ALL 이벤트 발행 (Section 15: 메서드 추출)
     */
    private void publishClearAllEvent() {
        invalidationCallback.accept(
                CacheInvalidationEvent.clearAll(getName(), instanceId)
        );
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        return wrapper != null ? type.cast(wrapper.get()) : null;
    }

    /**
     * 캐시 조회 with valueLoader (분산 Single-flight 적용)
     *
     * <h4>Issue #148: Cache Stampede 방지</h4>
     * <ul>
     *   <li>Leader: 락 획득 → Double-check L2 → valueLoader 실행 → L2 저장 → L1 저장</li>
     *   <li>Follower: 락 대기 → L2에서 읽기 → L1 Backfill</li>
     *   <li>락 실패 시: Fallback으로 직접 실행 (가용성 우선)</li>
     * </ul>
     *
     * <h4>#262 실험 결과</h4>
     * <p>Local Coalescing 시도 → L2 조회도 블로킹되어 RPS 33% 악화 → 롤백</p>
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        String keyStr = key.toString();
        TaskContext context = TaskContext.of("Cache", "GetWithLoader", keyStr);

        return executor.executeWithTranslation(
                () -> doGetWithSingleFlight(key, valueLoader, keyStr),
                ExceptionTranslator.forCache(key, valueLoader),
                context
        );
    }

    /**
     * Single-flight 패턴 구현 (구조적 분리 - CLAUDE.md 섹션 4, 11, 12 준수)
     *
     * <p><b>핵심 원칙:</b></p>
     * <ul>
     *   <li>Optional → 예외 없는 캐시 조회에만 사용</li>
     *   <li>checked exception → Optional 밖에서 직접 호출</li>
     *   <li>try-catch/RuntimeException 사용 금지</li>
     * </ul>
     */
    private <T> T doGetWithSingleFlight(Object key, Callable<T> valueLoader, String keyStr) throws Exception {
        // 1. L1 → L2 순차 조회 (Optional - 예외 없음)
        T cached = getCachedValueFromLayers(key);
        if (cached != null) {
            return cached;
        }

        // 2. 캐시 미스 → 분산 락으로 Single-flight (직접 호출 - 예외 자연 전파)
        return executeWithDistributedLock(key, valueLoader, keyStr);
    }

    /**
     * L1 → L2 순차 캐시 조회 (Graceful Degradation Pattern)
     *
     * <p><b>CLAUDE.md 섹션 12 패턴 3:</b> L2 장애 시 executeOrDefault로 null 반환</p>
     * <p><b>가용성 우선:</b> L2(Redis) 장애 시에도 valueLoader로 폴백</p>
     */
    @SuppressWarnings("unchecked")
    private <T> T getCachedValueFromLayers(Object key) {
        // L1 조회 (로컬 캐시 - 장애 없음)
        ValueWrapper l1Result = l1.get(key);
        if (l1Result != null) {
            recordCacheHit("L1");
            return (T) l1Result.get();
        }

        // L2 조회 (Redis - Graceful Degradation: 장애 시 null 반환)
        ValueWrapper l2Result = executor.executeOrDefault(
                () -> l2.get(key),
                null,
                TaskContext.of("Cache", "GetL2", key.toString())
        );

        if (l2Result != null) {
            l1.put(key, l2Result.get());
            recordCacheHit("L2");
            return (T) l2Result.get();
        }

        return null;
    }

    /**
     * 분산 락 획득 및 valueLoader 실행 (Graceful Degradation Pattern)
     *
     * <p><b>Watchdog 모드:</b> leaseTime 생략 → 30초마다 자동 연장</p>
     * <p><b>Graceful Degradation:</b> Redis 장애 시 락 획득 실패로 처리 → Fallback 실행</p>
     * <p><b>Context7 Best Practice:</b> 락 획득 성공 시에만 try-finally 진입</p>
     */
    private <T> T executeWithDistributedLock(Object key, Callable<T> valueLoader, String keyStr) throws Exception {
        String lockKey = buildLockKey(keyStr);
        RLock lock = redissonClient.getLock(lockKey);

        // Graceful Degradation: 락 획득 시도도 Redis 장애 허용 (CLAUDE.md 섹션 12 패턴 3)
        boolean acquired = executor.executeOrDefault(
                () -> lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS),
                false,  // Redis 장애 시 락 획득 실패로 처리
                TaskContext.of("Cache", "AcquireLock", keyStr)
        );

        // 락 획득 실패 또는 Redis 장애 → Fallback
        if (!acquired) {
            log.warn("[TieredCache] Lock acquisition failed, executing directly: {}", lockKey);
            recordLockFailure();
            return executeAndCache(key, valueLoader);
        }

        // Section 12 준수: try-finally → executeWithFinally
        return executor.executeWithFinally(
                () -> executeDoubleCheckAndLoad(key, valueLoader),
                () -> unlockSafelyDirect(lock),
                TaskContext.of("Cache", "DoubleCheckLoad", keyStr)
        );
    }

    /**
     * 락 해제 (Direct - executeWithFinally의 finallyBlock용)
     *
     * <p>Section 15: 메서드 참조로 중첩 회피</p>
     */
    private void unlockSafelyDirect(RLock lock) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /**
     * Double-check 후 valueLoader 실행 (Graceful Degradation Pattern)
     *
     * <p><b>CLAUDE.md 섹션 12 패턴 3:</b> L2 장애 시 executeOrDefault로 null 반환</p>
     * <p><b>가용성 우선:</b> Double-check 시에도 L2 장애 허용</p>
     */
    @SuppressWarnings("unchecked")
    private <T> T executeDoubleCheckAndLoad(Object key, Callable<T> valueLoader) throws Exception {
        // Double-check: L2 재확인 (Graceful Degradation: 장애 시 null 반환)
        ValueWrapper wrapper = executor.executeOrDefault(
                () -> l2.get(key),
                null,
                TaskContext.of("Cache", "DoubleCheckL2", key.toString())
        );

        if (wrapper != null) {
            l1.put(key, wrapper.get());
            return (T) wrapper.get();
        }

        // 캐시 미스 → valueLoader 실행 (직접 호출 - 예외 자연 전파)
        recordCacheMiss();
        return executeAndCache(key, valueLoader);
    }

    /**
     * valueLoader 실행 및 캐시 저장 (L2 → L1 순서)
     */
    private <T> T executeAndCache(Object key, Callable<T> valueLoader) throws Exception {
        T value = valueLoader.call();

        boolean l2Success = executor.executeOrDefault(
                () -> { l2.put(key, value); return true; },
                false,
                TaskContext.of("Cache", "PutL2", key.toString())
        );

        if (l2Success) {
            l1.put(key, value);
        } else {
            log.warn("[TieredCache] L2 put failed, skipping L1: key={}", key);
            recordL2Failure();
        }

        return value;
    }

    // ==================== Helper Methods ====================

    private String buildLockKey(String keyStr) {
        return "cache:sf:" + l2.getName() + ":" + keyStr.hashCode();
    }

    /**
     * Tap 패턴: 값 반환하며 캐시 히트 메트릭 기록
     */
    private ValueWrapper tapCacheHit(ValueWrapper wrapper, String layer) {
        recordCacheHit(layer);
        return wrapper;
    }

    // ==================== Metrics (Micrometer 소문자 점 표기법) ====================

    private void recordCacheHit(String layer) {
        meterRegistry.counter("cache.hit", "layer", layer).increment();
    }

    private void recordCacheMiss() {
        meterRegistry.counter("cache.miss").increment();
    }

    private void recordLockFailure() {
        meterRegistry.counter("cache.lock.failure").increment();
    }

    private void recordL2Failure() {
        meterRegistry.counter("cache.l2.failure").increment();
    }
}
