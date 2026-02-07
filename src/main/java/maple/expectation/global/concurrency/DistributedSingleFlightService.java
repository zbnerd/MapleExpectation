package maple.expectation.global.concurrency;

import java.time.Duration;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.lock.LockStrategy;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 분산 Single-Flight 서비스 (Issue #283 P0-4)
 *
 * <h3>Scale-out 대응</h3>
 *
 * <p>기존 SingleFlightExecutor는 인스턴스 레벨에서만 중복 계산을 방지합니다. 이 서비스는 Redis 기반 분산 락 + 캐시를 사용하여 멀티 인스턴스
 * 환경에서도 동일 키에 대한 중복 계산을 방지합니다.
 *
 * <h4>동작 방식</h4>
 *
 * <pre>
 * 1. Redis 캐시 확인 → HIT: 즉시 반환
 * 2. 분산 락 획득 시도 → 실패: 캐시 재확인 후 대기
 * 3. 계산 실행 → 결과를 Redis에 캐시 (short TTL)
 * 4. 락 해제
 * </pre>
 *
 * @see SingleFlightExecutor 인스턴스 레벨 Single-Flight (비동기)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedSingleFlightService {

  private final RedissonClient redissonClient;
  private final LockStrategy lockStrategy;
  private final LogicExecutor executor;

  private static final String CACHE_PREFIX = "{single-flight}:result:";
  private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);

  /**
   * 분산 Single-Flight 실행
   *
   * @param key 계산 식별 키
   * @param computation 실제 계산 로직
   * @param cacheTtl 결과 캐시 TTL
   * @param <T> 결과 타입 (Serializable 필수)
   * @return 계산 결과
   */
  public <T> T executeOrShare(String key, Supplier<T> computation, Duration cacheTtl) {
    return executor.execute(
        () -> doExecuteOrShare(key, computation, cacheTtl),
        TaskContext.of("SingleFlight", "Distributed", key));
  }

  /** 분산 Single-Flight 실행 (기본 TTL 30초) */
  public <T> T executeOrShare(String key, Supplier<T> computation) {
    return executeOrShare(key, computation, DEFAULT_CACHE_TTL);
  }

  private <T> T doExecuteOrShare(String key, Supplier<T> computation, Duration cacheTtl)
      throws Throwable {
    String cacheKey = CACHE_PREFIX + key;

    // Step 1: Check Redis cache
    T cached = getCachedResult(cacheKey);
    if (cached != null) {
      log.debug("[DistributedSingleFlight] Cache HIT: {}", key);
      return cached;
    }

    // Step 2: Acquire distributed lock and compute
    return lockStrategy.executeWithLock(
        "single-flight:" + key, 5, 30, () -> computeAndCache(key, cacheKey, computation, cacheTtl));
  }

  private <T> T getCachedResult(String cacheKey) {
    return executor.executeOrDefault(
        () -> {
          RBucket<T> bucket = redissonClient.getBucket(cacheKey);
          return bucket.get();
        },
        null,
        TaskContext.of("SingleFlight", "CacheGet"));
  }

  private <T> T computeAndCache(
      String key, String cacheKey, Supplier<T> computation, Duration cacheTtl) {
    // Double-check cache after acquiring lock
    T existing = getCachedResult(cacheKey);
    if (existing != null) {
      log.debug("[DistributedSingleFlight] Cache HIT after lock: {}", key);
      return existing;
    }

    T result = computation.get();

    // Cache result with TTL
    executor.executeVoid(
        () -> redissonClient.<T>getBucket(cacheKey).set(result, cacheTtl),
        TaskContext.of("SingleFlight", "CacheSet", key));

    log.debug("[DistributedSingleFlight] Computed and cached: {}", key);
    return result;
  }
}
