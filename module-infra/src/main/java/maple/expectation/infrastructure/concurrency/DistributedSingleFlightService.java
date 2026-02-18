package maple.expectation.infrastructure.concurrency;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.DistributedLockException;
import maple.expectation.infrastructure.executor.CheckedLogicExecutor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.lock.LockStrategy;
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

  private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);
  private static final String CACHE_PREFIX = "{single-flight}:result:";
  private static final int MAX_RETRIES = 6;
  private static final long BASE_DELAY_MS = 50L;

  private final LogicExecutor executor;
  private final CheckedLogicExecutor checkedExecutor;
  private final LockStrategy lockStrategy;
  private final RedissonClient redissonClient;

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
    if (key == null || key.trim().isEmpty()) {
      throw new IllegalArgumentException("Key must not be null or empty");
    }
    String cacheKey = CACHE_PREFIX + key;

    // Step 1: Check Redis cache
    T cached = getCachedResult(cacheKey);
    if (cached != null) {
      log.debug("[DistributedSingleFlight] Cache HIT: {}", key);
      return cached;
    }

    // Step 2: Acquire distributed lock and compute
    // If lock timeout occurs, retry cache reads (another instance may be computing)
    return executor.executeOrCatch(
        () ->
            lockStrategy.executeWithLock(
                "single-flight:" + key,
                5,
                30,
                () -> computeAndCache(key, cacheKey, computation, cacheTtl)),
        e -> {
          if (e instanceof DistributedLockException) {
            log.debug("[DistributedSingleFlight] Lock timeout, retrying cache read: {}", key);
            return executor.execute(
                () -> retryCacheRead(cacheKey, key),
                TaskContext.of("SingleFlight", "RetryCacheRead", key));
          }
          throw new DistributedLockException("Lock execution failed", e);
        },
        TaskContext.of("SingleFlight", "ExecuteWithLock", key));
  }

  /**
   * Retry cache read with exponential backoff when lock acquisition fails.
   *
   * <p>This handles the case where another instance is computing the result and we should wait for
   * it to complete instead of failing immediately.
   *
   * @param cacheKey Redis cache key
   * @param originalKey Original key for logging
   * @return Cached result or throws if computation fails
   */
  private <T> T retryCacheRead(String cacheKey, String originalKey) throws Throwable {
    return getCachedResultWithRetry(cacheKey, originalKey);
  }

  /**
   * Retry cache read with exponential backoff when lock acquisition fails.
   *
   * <p>This handles the case where another instance is computing the result and we should wait for
   * it to complete instead of failing immediately.
   *
   * @param cacheKey Redis cache key
   * @param originalKey Original key for logging
   * @return Cached result or throws if computation fails
   */
  private <T> T getCachedResultWithRetry(String cacheKey, String originalKey) throws Throwable {
    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
      T cached = getCachedResult(cacheKey);
      if (cached != null) {
        log.debug(
            "[DistributedSingleFlight] Cache HIT after retry (attempt {}): {}",
            attempt + 1,
            originalKey);
        return cached;
      }

      sleepWithBackoff(attempt, originalKey);
    }

    return handleFinalRetry(cacheKey, originalKey);
  }

  /** Sleep with exponential backoff during cache retry. */
  private <T> void sleepWithBackoff(int attempt, String originalKey) throws Throwable {
    long delayMs =
        BASE_DELAY_MS * (1L << attempt); // Exponential backoff: 50ms, 100ms, 200ms, 400ms...
    long jitter = ThreadLocalRandom.current().nextLong(0, delayMs / 4);
    long totalDelay = delayMs + jitter;

    // Use LogicExecutor pattern instead of direct Thread.sleep
    executor.executeVoid(
        () -> {
          try {
            Thread.sleep(totalDelay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new DistributedLockException(
                "Cache read retry interrupted [key=" + originalKey + "]", ie);
          }
        },
        TaskContext.of("SingleFlight", "SleepBackoff", String.valueOf(attempt)));
  }

  /** Handle final retry after all exponential backoff attempts. */
  private <T> T handleFinalRetry(String cacheKey, String originalKey) throws Throwable {
    T finalCached = getCachedResult(cacheKey);
    if (finalCached != null) {
      log.debug("[DistributedSingleFlight] Cache HIT after final retry: {}", originalKey);
      return finalCached;
    }

    log.error(
        "[DistributedSingleFlight] Cache miss after all retries, computation may have failed: {}",
        originalKey);
    throw new DistributedLockException(
        "Failed to acquire lock and no cached result available after "
            + MAX_RETRIES
            + " retries [key="
            + originalKey
            + "]");
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
