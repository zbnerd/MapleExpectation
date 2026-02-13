package maple.expectation.infrastructure.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationEvent;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;

/**
 * 2층 구조 캐시 (L1: Caffeine, L2: Redis)
 *
 * <h4>Issue #148: Race Condition 제거 및 L1/L2 일관성 보장</h4>
 *
 * <ul>
 *   <li>Cache Stampede 방지: Redisson 분산 락 기반 Single-flight 패턴
 *   <li>Layer Consistency: L2 저장 성공 시에만 L1 저장
 *   <li>Watchdog 모드: leaseTime 생략으로 자동 연장
 * </ul>
 *
 * <h4>P0/P1 리팩토링</h4>
 *
 * <ul>
 *   <li>P0-1: put() 시 Pub/Sub 이벤트 발행 (원격 인스턴스 L1 무효화)
 *   <li>P0-3: evict() 순서 L2→L1→Pub/Sub (stale backfill 방지)
 *   <li>P0-4: lockWaitSeconds 외부화 (30초→5초, cold burst 방지)
 *   <li>P1-1: 메트릭에 cacheName 태그 추가
 *   <li>P1-6: Supplier 기반 lazy resolution (instanceId, callback)
 *   <li>P1-7: Counter pre-registration (hot-path 할당 제거)
 * </ul>
 *
 * @see <a href="https://github.com/issue/148">Issue #148</a>
 */
@Slf4j
public class TieredCache implements Cache {
  private final Cache l1; // Caffeine (Local)
  private final Cache l2; // Redis (Distributed)
  private final LogicExecutor executor;
  private final RedissonClient redissonClient; // 분산 락용
  private final int lockWaitSeconds; // P0-4: 외부 설정 (기본 5초)
  private final Supplier<String> instanceIdSupplier; // P1-6: Lazy Resolution
  private final Supplier<Consumer<CacheInvalidationEvent>>
      callbackSupplier; // P1-6: Lazy Resolution

  // P1-7: Counter pre-registration (hot-path 할당 제거)
  private final Counter l1HitCounter;
  private final Counter l2HitCounter;
  private final Counter missCounter;
  private final Counter lockFailureCounter;
  private final Counter l2FailureCounter;

  public TieredCache(
      Cache l1,
      Cache l2,
      LogicExecutor executor,
      RedissonClient redissonClient,
      MeterRegistry meterRegistry,
      int lockWaitSeconds,
      Supplier<String> instanceIdSupplier,
      Supplier<Consumer<CacheInvalidationEvent>> callbackSupplier) {
    this.l1 = l1;
    this.l2 = l2;
    this.executor = executor;
    this.redissonClient = redissonClient;
    this.lockWaitSeconds = lockWaitSeconds;
    this.instanceIdSupplier = instanceIdSupplier;
    this.callbackSupplier = callbackSupplier;

    // P1-7 + P1-1: 생성자에서 Counter pre-register (cacheName 태그 포함)
    String cacheName = l2.getName();
    this.l1HitCounter =
        Counter.builder("cache.hit")
            .tag("layer", "L1")
            .tag("cache", cacheName)
            .register(meterRegistry);
    this.l2HitCounter =
        Counter.builder("cache.hit")
            .tag("layer", "L2")
            .tag("cache", cacheName)
            .register(meterRegistry);
    this.missCounter =
        Counter.builder("cache.miss").tag("cache", cacheName).register(meterRegistry);
    this.lockFailureCounter =
        Counter.builder("cache.lock.failure").tag("cache", cacheName).register(meterRegistry);
    this.l2FailureCounter =
        Counter.builder("cache.l2.failure").tag("cache", cacheName).register(meterRegistry);
  }

  @Override
  public String getName() {
    return l2.getName();
  }

  @Override
  public Object getNativeCache() {
    return l2.getNativeCache();
  }

  /**
   * 캐시 조회 (L1 → L2 순서, Backfill 포함)
   *
   * <p>CLAUDE.md 섹션 15 준수: 람다 → 메서드 참조
   */
  @Override
  public ValueWrapper get(Object key) {
    TaskContext context = TaskContext.of("Cache", "Get", key.toString());
    return executor.execute(() -> getFromCacheLayers(key), context);
  }

  /** L1 → L2 순차 조회 (Optional 체이닝 - Modern Java 스타일) */
  private ValueWrapper getFromCacheLayers(Object key) {
    return Optional.ofNullable(l1.get(key))
        .map(w -> tapCacheHit(w, "L1"))
        .orElseGet(() -> getFromL2WithBackfill(key));
  }

  /** L2 조회 및 L1 Backfill (Optional 체이닝) */
  private ValueWrapper getFromL2WithBackfill(Object key) {
    return Optional.ofNullable(l2.get(key))
        .map(
            w -> {
              l1.put(key, w.get()); // Backfill
              return tapCacheHit(w, "L2");
            })
        .orElse(null);
  }

  /**
   * 캐시 저장 (L2 → L1 순서 - 일관성 보장)
   *
   * <h4>Issue #148: L2 저장 성공 시에만 L1 저장</h4>
   *
   * <h4>P0-1: put() 성공 시 Pub/Sub 이벤트 발행 (원격 인스턴스 L1 무효화)</h4>
   */
  @Override
  public void put(Object key, Object value) {
    TaskContext context = TaskContext.of("Cache", "Put", key.toString());

    boolean l2Success =
        executor.executeOrDefault(
            () -> {
              l2.put(key, value);
              return true;
            },
            false,
            context);

    if (l2Success) {
      executor.executeVoid(() -> l1.put(key, value), context);
      publishEvictEvent(key); // P0-1: 원격 인스턴스 L1 evict
    } else {
      log.warn("[TieredCache] L2 put failed, skipping L1 for consistency: key={}", key);
      l2FailureCounter.increment();
    }
  }

  /**
   * 캐시 키 무효화 (L2 → L1 → Pub/Sub 전파)
   *
   * <h4>P0-3 Fix: evict() 순서 L2→L1→Pub/Sub</h4>
   *
   * <p>L2가 source of truth. L2 먼저 제거해야 backfill 시 stale data 불가.
   *
   * <h4>Issue #278: L1 Cache Coherence (P0-1)</h4>
   *
   * <p>evict 후 다른 인스턴스의 L1 캐시도 무효화하도록 이벤트 발행
   *
   * <h4>P1 Fix: L1 장애 격리 - L2 실패 시에도 L1 evict 보장</h4>
   *
   * <p>L2(Redis) 장애 시 l2.evict()가 예외를 던져도 L1 무효화는 반드시 수행해야 함. 따라서 L2 evict를 별도 executeOrDefault로 감싸
   * 실패를 허용하고, L1 evict는 항상 실행.
   */
  @Override
  public void evict(Object key) {
    TaskContext context = TaskContext.of("Cache", "Evict", key.toString());

    // L2 evict는 장애 허용 (Graceful Degradation)
    boolean l2Success =
        executor.executeOrDefault(
            () -> {
              l2.evict(key);
              return true;
            },
            false,
            context);

    if (!l2Success) {
      log.warn("[TieredCache] L2 evict failed, proceeding with L1: key={}", key);
      l2FailureCounter.increment();
    }

    // L1 evict는 항상 실행 (로컬 장애 없음, L2 실패와 무관)
    executor.executeVoid(() -> l1.evict(key), context);

    // Pub/Sub 이벤트 발행 (L2 성공 여부와 무관)
    publishEvictEvent(key);
  }

  /**
   * 캐시 전체 무효화 (L2 → L1 → Pub/Sub 전파)
   *
   * <h4>P0-3 일관성: L2 → L1 순서</h4>
   *
   * <h4>Issue #278: L1 Cache Coherence (P0-1)</h4>
   *
   * <p>clear 후 다른 인스턴스의 L1 캐시도 전체 무효화하도록 이벤트 발행
   *
   * <h4>P1 Fix: L1 장애 격리 - L2 실패 시에도 L1 clear 보장</h4>
   *
   * <p>L2(Redis) 장애 시 l2.clear()가 예외를 던져도 L1 무효화는 반드시 수행해야 함. 따라서 L2 clear를 별도 executeOrDefault로 감싸
   * 실패를 허용하고, L1 clear는 항상 실행.
   */
  @Override
  public void clear() {
    TaskContext context = TaskContext.of("Cache", "Clear");

    // L2 clear는 장애 허용 (Graceful Degradation)
    boolean l2Success =
        executor.executeOrDefault(
            () -> {
              l2.clear();
              return true;
            },
            false,
            context);

    if (!l2Success) {
      log.warn("[TieredCache] L2 clear failed, proceeding with L1");
      l2FailureCounter.increment();
    }

    // L1 clear는 항상 실행 (로컬 장애 없음, L2 실패와 무관)
    executor.executeVoid(() -> l1.clear(), context);

    // Pub/Sub 이벤트 발행 (L2 성공 여부와 무관)
    publishClearAllEvent();
  }

  /** EVICT 이벤트 발행 (Section 15: 메서드 추출) */
  private void publishEvictEvent(Object key) {
    callbackSupplier
        .get()
        .accept(CacheInvalidationEvent.evict(getName(), key.toString(), instanceIdSupplier.get()));
  }

  /** CLEAR_ALL 이벤트 발행 (Section 15: 메서드 추출) */
  private void publishClearAllEvent() {
    callbackSupplier
        .get()
        .accept(CacheInvalidationEvent.clearAll(getName(), instanceIdSupplier.get()));
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
   *
   * <ul>
   *   <li>Leader: 락 획득 → Double-check L2 → valueLoader 실행 → L2 저장 → L1 저장
   *   <li>Follower: 락 대기 → L2에서 읽기 → L1 Backfill
   *   <li>락 실패 시: Fallback으로 직접 실행 (가용성 우선)
   * </ul>
   *
   * <h4>#262 실험 결과</h4>
   *
   * <p>Local Coalescing 시도 → L2 조회도 블로킹되어 RPS 33% 악화 → 롤백
   */
  @Override
  @SuppressWarnings("unchecked")
  public <T> T get(Object key, Callable<T> valueLoader) {
    String keyStr = key.toString();
    TaskContext context = TaskContext.of("Cache", "GetWithLoader", keyStr);

    return executor.executeWithTranslation(
        () -> doGetWithSingleFlight(key, valueLoader, keyStr),
        ExceptionTranslator.forCache(key, valueLoader),
        context);
  }

  /**
   * Single-flight 패턴 구현 (구조적 분리 - CLAUDE.md 섹션 4, 11, 12 준수)
   *
   * <p><b>핵심 원칙:</b>
   *
   * <ul>
   *   <li>Optional → 예외 없는 캐시 조회에만 사용
   *   <li>checked exception → Optional 밖에서 직접 호출
   *   <li>try-catch/RuntimeException 사용 금지
   * </ul>
   */
  private <T> T doGetWithSingleFlight(Object key, Callable<T> valueLoader, String keyStr)
      throws Exception {
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
   * <p><b>CLAUDE.md 섹션 12 패턴 3:</b> L2 장애 시 executeOrDefault로 null 반환
   *
   * <p><b>가용성 우선:</b> L2(Redis) 장애 시에도 valueLoader로 폴백
   */
  @SuppressWarnings("unchecked")
  private <T> T getCachedValueFromLayers(Object key) {
    // L1 조회 (로컬 캐시 - 장애 없음)
    ValueWrapper l1Result = l1.get(key);
    if (l1Result != null) {
      l1HitCounter.increment();
      return (T) l1Result.get();
    }

    // L2 조회 (Redis - Graceful Degradation: 장애 시 null 반환)
    ValueWrapper l2Result =
        executor.executeOrDefault(
            () -> l2.get(key), null, TaskContext.of("Cache", "GetL2", key.toString()));

    if (l2Result != null) {
      l1.put(key, l2Result.get());
      l2HitCounter.increment();
      return (T) l2Result.get();
    }

    return null;
  }

  /**
   * 분산 락 획득 및 valueLoader 실행 (Graceful Degradation Pattern)
   *
   * <p><b>Watchdog 모드:</b> leaseTime 생략 → 30초마다 자동 연장
   *
   * <p><b>Graceful Degradation:</b> Redis 장애 시 락 획득 실패로 처리 → Fallback 실행
   *
   * <p><b>Context7 Best Practice:</b> 락 획득 성공 시에만 try-finally 진입
   */
  private <T> T executeWithDistributedLock(Object key, Callable<T> valueLoader, String keyStr)
      throws Exception {
    String lockKey = buildLockKey(keyStr);
    RLock lock = redissonClient.getLock(lockKey);

    // Graceful Degradation: 락 획득 시도도 Redis 장애 허용 (CLAUDE.md 섹션 12 패턴 3)
    boolean acquired =
        executor.executeOrDefault(
            () -> lock.tryLock(lockWaitSeconds, TimeUnit.SECONDS),
            false, // Redis 장애 시 락 획득 실패로 처리
            TaskContext.of("Cache", "AcquireLock", keyStr));

    // 락 획득 실패 또는 Redis 장애 → Fallback
    if (!acquired) {
      log.warn("[TieredCache] Lock acquisition failed, executing directly: {}", lockKey);
      lockFailureCounter.increment();
      return executeAndCache(key, valueLoader);
    }

    // Section 12 준수: try-finally → executeWithFinally
    return executor.executeWithFinally(
        () -> executeDoubleCheckAndLoad(key, valueLoader),
        () -> unlockSafelyDirect(lock),
        TaskContext.of("Cache", "DoubleCheckLoad", keyStr));
  }

  /**
   * 락 해제 (Direct - executeWithFinally의 finallyBlock용)
   *
   * <p>Section 15: 메서드 참조로 중첩 회피
   */
  private void unlockSafelyDirect(RLock lock) {
    if (lock.isHeldByCurrentThread()) {
      lock.unlock();
    }
  }

  /**
   * Double-check 후 valueLoader 실행 (Graceful Degradation Pattern)
   *
   * <p><b>CLAUDE.md 섹션 12 패턴 3:</b> L2 장애 시 executeOrDefault로 null 반환
   *
   * <p><b>가용성 우선:</b> Double-check 시에도 L2 장애 허용
   */
  @SuppressWarnings("unchecked")
  private <T> T executeDoubleCheckAndLoad(Object key, Callable<T> valueLoader) throws Exception {
    // Double-check: L2 재확인 (Graceful Degradation: 장애 시 null 반환)
    ValueWrapper wrapper =
        executor.executeOrDefault(
            () -> l2.get(key), null, TaskContext.of("Cache", "DoubleCheckL2", key.toString()));

    if (wrapper != null) {
      l1.put(key, wrapper.get());
      return (T) wrapper.get();
    }

    // 캐시 미스 → valueLoader 실행 (직접 호출 - 예외 자연 전파)
    missCounter.increment();
    return executeAndCache(key, valueLoader);
  }

  /** valueLoader 실행 및 캐시 저장 (L2 → L1 순서) */
  private <T> T executeAndCache(Object key, Callable<T> valueLoader) throws Exception {
    T value = valueLoader.call();

    boolean l2Success =
        executor.executeOrDefault(
            () -> {
              l2.put(key, value);
              return true;
            },
            false,
            TaskContext.of("Cache", "PutL2", key.toString()));

    if (l2Success) {
      l1.put(key, value);
    } else {
      log.warn("[TieredCache] L2 put failed, skipping L1: key={}", key);
      l2FailureCounter.increment();
    }

    return value;
  }

  // ==================== Helper Methods ====================

  private String buildLockKey(String keyStr) {
    return "cache:sf:" + l2.getName() + ":" + keyStr;
  }

  /** Tap 패턴: 값 반환하며 캐시 히트 메트릭 기록 */
  private ValueWrapper tapCacheHit(ValueWrapper wrapper, String layer) {
    if ("L1".equals(layer)) {
      l1HitCounter.increment();
    } else {
      l2HitCounter.increment();
    }
    return wrapper;
  }
}
