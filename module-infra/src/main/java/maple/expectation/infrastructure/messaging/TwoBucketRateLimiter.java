package maple.expectation.infrastructure.messaging;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.common.resource.ResourceLoader;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.ratelimit.exception.RateLimitExceededException;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Two-bucket rate limiter combining Token Bucket and Leaky Bucket algorithms.
 *
 * <p><strong>Design Pattern:</strong> Strategy Pattern with Lua Script atomic execution. Provides
 * both burst handling (Token Bucket) and sustained rate limiting (Leaky Bucket).
 *
 * <p><strong>Redis Cluster Compatibility:</strong> Uses Hash Tag {userId} pattern to ensure all
 * keys map to the same cluster slot. (Section 8-1: infrastructure.md)
 *
 * <p><strong>Algorithm:</strong>
 *
 * <ul>
 *   <li>Token Bucket: Allows burst traffic up to capacity
 *   <li>Leaky Bucket: Refills tokens at constant rate (sustained RPS limit)
 *   <li>Hybrid: Best of both - burst allowance + steady rate enforcement
 * </ul>
 *
 * <p><strong>CLAUDE.md Section 4 Compliance:</strong>
 *
 * <ul>
 *   <li>SRP: Single responsibility - rate limiting only
 *   <li>OCP: Pluggable algorithm via Lua Script
 *   <li>LogicExecutor: Exception handling via executeWithTranslation
 * </ul>
 *
 * <h3>Lua Script Contract (rate_limit_check.lua):</h3>
 *
 * <pre>
 * KEYS[1] = {event:rate}:{userId}
 * ARGV[1] = requests
 * ARGV[2] = capacity (burst size)
 * ARGV[3] = refillRate (tokens per second)
 * ARGV[4] = currentTimeSeconds
 * ARGV[5] = ttlSeconds
 *
 * Returns: {status, remainingTokens, retryAfterSeconds}
 * </pre>
 *
 * <h3>Configuration:</h3>
 *
 * <ul>
 *   <li>{@code app.messaging.rate-limit.capacity}: Burst capacity (default: 500)
 *   <li>{@code app.messaging.rate-limit.refill-rate}: Sustained RPS (default: 500)
 *   <li>{@code app.messaging.rate-limit.ttl-seconds}: State TTL (default: 3600)
 * </ul>
 *
 * @see maple.expectation.infrastructure.messaging.UpdateRequestCoalescer
 * @see maple.expectation.error.exception.RateLimitExceededException
 */
@Slf4j
@Component
public class TwoBucketRateLimiter {

  private static final String LUA_RATE_LIMIT_CHECK = "lua/event/rate_limit_check.lua";
  private static final int DEFAULT_CAPACITY = 500;
  private static final int DEFAULT_REFILL_RATE = 500;
  private static final int DEFAULT_TTL_SECONDS = 3600;

  private final RedissonClient redissonClient;
  private final LogicExecutor executor;
  private final ResourceLoader resourceLoader;
  private final int capacity;
  private final int refillRate;
  private final int ttlSeconds;

  public TwoBucketRateLimiter(
      RedissonClient redissonClient,
      LogicExecutor executor,
      ResourceLoader resourceLoader,
      @Value("${app.messaging.rate-limit.capacity:500}") int capacity,
      @Value("${app.messaging.rate-limit.refill-rate:500}") int refillRate,
      @Value("${app.messaging.rate-limit.ttl-seconds:3600}") int ttlSeconds) {
    this.redissonClient = redissonClient;
    this.executor = executor;
    this.resourceLoader = resourceLoader;
    this.capacity = capacity;
    this.refillRate = refillRate;
    this.ttlSeconds = ttlSeconds;
  }

  /**
   * Result of rate limit check.
   *
   * @param allowed Whether request is allowed
   * @param remainingTokens Tokens remaining in bucket
   * @param retryAfterSeconds Seconds until next token available (0 if allowed)
   */
  public record RateLimitResult(boolean allowed, int remainingTokens, int retryAfterSeconds) {

    public static RateLimitResult allowed(int remainingTokens) {
      return new RateLimitResult(true, remainingTokens, 0);
    }

    public static RateLimitResult rejected(int retryAfterSeconds) {
      return new RateLimitResult(false, 0, retryAfterSeconds);
    }
  }

  /**
   * Check if request should be rate limited.
   *
   * <p>Uses Lua Script for atomic token bucket operations.
   *
   * @param userId User identifier (for Hash Tag)
   * @param requests Number of tokens requested
   * @return RateLimitResult with decision and metadata
   * @throws RateLimitExceededException if rate limit exceeded
   */
  public RateLimitResult checkLimit(String userId, int requests) {
    return executor.executeWithTranslation(
        () -> checkLimitInternal(userId, requests),
        this::translateRateLimitException,
        TaskContext.of("TwoBucketRateLimiter", "CheckLimit", userId));
  }

  /**
   * Internal rate limit check with checked exceptions.
   *
   * <p>Loads Lua Script and executes with Redisson RScript.
   */
  private RateLimitResult checkLimitInternal(String userId, int requests) throws Exception {
    // Load Lua Script
    String luaScript = resourceLoader.loadResourceAsString(LUA_RATE_LIMIT_CHECK);

    // Build Hash Tag key for Redis Cluster compatibility
    String rateKey = buildRateKey(userId);

    // Current time in seconds (Lua script precision)
    long currentTime = System.currentTimeMillis() / 1000;

    RScript script = redissonClient.getScript(StringCodec.INSTANCE);

    // Execute Lua Script
    @SuppressWarnings("unchecked")
    List<Object> result =
        script.eval(
            RScript.Mode.READ_WRITE,
            luaScript,
            RScript.ReturnType.MULTI,
            List.of(rateKey),
            String.valueOf(requests),
            String.valueOf(capacity),
            String.valueOf(refillRate),
            String.valueOf(currentTime),
            String.valueOf(ttlSeconds));

    // Parse result: {status, remainingTokens, retryAfterSeconds}
    String status = (String) result.get(0);
    int remainingTokens = Integer.parseInt((String) result.get(1));
    int retryAfterSeconds = Integer.parseInt((String) result.get(2));

    boolean allowed = "ALLOWED".equals(status);

    log.debug(
        "[TwoBucketRateLimiter] Rate limit check: userId={}, allowed={}, remainingTokens={}, retryAfter={}",
        userId,
        allowed,
        remainingTokens,
        retryAfterSeconds);

    if (!allowed) {
      log.warn(
          "[TwoBucketRateLimiter] Rate limit exceeded: userId={}, retryAfter={}s, capacity={}, refillRate={}",
          userId,
          retryAfterSeconds,
          capacity,
          refillRate);
    }

    return allowed
        ? RateLimitResult.allowed(remainingTokens)
        : RateLimitResult.rejected(retryAfterSeconds);
  }

  /**
   * Check limit and throw exception if exceeded.
   *
   * <p>Convenience method for direct use in business logic.
   *
   * @param userId User identifier
   * @param requests Number of tokens requested
   * @throws RateLimitExceededException if rate limit exceeded
   */
  public void checkLimitOrThrow(String userId, int requests) {
    RateLimitResult result = checkLimit(userId, requests);
    if (!result.allowed()) {
      throw new RateLimitExceededException(
          userId, result.retryAfterSeconds(), capacity, refillRate);
    }
  }

  /**
   * Get current token count for a user.
   *
   * <p>Non-blocking read operation for monitoring/decision making.
   *
   * @param userId User identifier
   * @return Current token count (0 if no data)
   */
  public int getCurrentTokens(String userId) {
    return executor.executeOrDefault(
        () -> getCurrentTokensInternal(userId),
        capacity,
        TaskContext.of("TwoBucketRateLimiter", "GetCurrentTokens", userId));
  }

  private int getCurrentTokensInternal(String userId) {
    String rateKey = buildRateKey(userId);
    var bucket = redissonClient.getBucket(rateKey, StringCodec.INSTANCE);
    Object tokens = bucket.get();
    return tokens != null ? Integer.parseInt(tokens.toString()) : capacity;
  }

  /**
   * Reset rate limit state for a user (admin operation).
   *
   * <p>Clears token bucket state, allowing full capacity.
   *
   * @param userId User identifier
   */
  public void reset(String userId) {
    executor.executeVoid(
        () -> {
          String rateKey = buildRateKey(userId);
          redissonClient.getBucket(rateKey, StringCodec.INSTANCE).delete();
          log.info("[TwoBucketRateLimiter] Reset rate limit: userId={}", userId);
        },
        TaskContext.of("TwoBucketRateLimiter", "Reset", userId));
  }

  /**
   * Exception translator for rate limit errors.
   *
   * <p>Converts generic script exceptions to domain-specific RateLimitExceededException.
   *
   * @param cause Original exception
   * @param context Task context for error tracking
   * @return Translated domain exception
   */
  private RateLimitExceededException translateRateLimitException(
      Throwable cause, TaskContext context) {
    if (cause instanceof RateLimitExceededException) {
      return (RateLimitExceededException) cause;
    }
    // Script execution error - wrap with context
    return new RateLimitExceededException("unknown", 0, capacity, refillRate, cause);
  }

  // Hash Tag pattern for Redis Cluster (Section 8-1)
  private String buildRateKey(String userId) {
    return "{event:rate}:" + userId;
  }
}
