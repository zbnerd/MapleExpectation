package maple.expectation.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.function.Supplier;
import maple.expectation.domain.repository.RedisRefreshTokenRepository;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Test utility for awaitility-based asynchronous assertions.
 *
 * <p>Provides helper methods for waiting on Redis operations in integration tests, eliminating the
 * Thread.sleep() anti-pattern that causes flaky tests.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * TestAwaitilityHelper.await().untilRedisKeyPresent(repository, tokenId);
 * TestAwaitilityHelper.await().untilRedisKeyAbsent(redisTemplate, key);
 * }</pre>
 *
 * @see <a href="https://github.com/awaitility/awaitility">Awaitility Documentation</a>
 * @since 0.0.1
 */
public final class TestAwaitilityHelper {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);

  private TestAwaitilityHelper() {
    // Utility class - prevent instantiation
  }

  /**
   * Returns the default awaitility helper instance.
   *
   * @return A new AwaitilityHelper with default timeout and poll interval
   */
  public static AwaitilityHelper await() {
    return new AwaitilityHelper(DEFAULT_TIMEOUT, DEFAULT_POLL_INTERVAL);
  }

  /**
   * Returns a customized awaitility helper instance.
   *
   * @param timeout Maximum time to wait for condition
   * @param pollInterval Interval between condition checks
   * @return A new AwaitilityHelper with custom timeout and poll interval
   */
  public static AwaitilityHelper await(Duration timeout, Duration pollInterval) {
    return new AwaitilityHelper(timeout, pollInterval);
  }

  /**
   * Helper class for fluent awaitility assertions.
   *
   * <p>Provides type-safe methods for common Redis operations in tests.
   */
  public static final class AwaitilityHelper {

    private final ConditionFactory conditionFactory;

    private AwaitilityHelper(Duration timeout, Duration pollInterval) {
      this.conditionFactory = Awaitility.await().atMost(timeout).pollInterval(pollInterval);
    }

    /**
     * Waits until a Redis key is present in the repository.
     *
     * @param repository The Redis repository to query
     * @param tokenId The token ID to wait for
     */
    public void untilRedisKeyPresent(RedisRefreshTokenRepository repository, String tokenId) {
      untilAsserted(
          () ->
              assertThat(repository.findById(tokenId))
                  .as("Redis key should be present: " + tokenId)
                  .isPresent());
    }

    /**
     * Waits until a Redis key is absent (deleted or expired).
     *
     * @param repository The Redis repository to query
     * @param tokenId The token ID to wait for absence
     */
    public void untilRedisKeyAbsent(RedisRefreshTokenRepository repository, String tokenId) {
      untilAsserted(
          () ->
              assertThat(repository.findById(tokenId))
                  .as("Redis key should be absent: " + tokenId)
                  .isEmpty());
    }

    /**
     * Waits until a Redis hash key is absent (deleted or expired).
     *
     * @param redisTemplate The Redis template to query
     * @param key The hash key to wait for absence
     */
    public void untilRedisKeyAbsent(StringRedisTemplate redisTemplate, String key) {
      untilAsserted(
          () ->
              assertThat(redisTemplate.hasKey(key))
                  .as("Redis key should be absent: " + key)
                  .isFalse());
    }

    /**
     * Waits until a Redis hash key has no more entries (empty hash).
     *
     * @param redisTemplate The Redis template to query
     * @param key The hash key to wait for emptiness
     */
    public void untilRedisHashEmpty(StringRedisTemplate redisTemplate, String key) {
      untilAsserted(
          () ->
              assertThat(redisTemplate.opsForHash().size(key))
                  .as("Redis hash should be empty: " + key)
                  .isEqualTo(0L));
    }

    /**
     * Waits until a Redis hash key no longer exists (deleted).
     *
     * @param redisTemplate The Redis template to query
     * @param key The hash key to wait for deletion
     */
    public void untilRedisHashDeleted(StringRedisTemplate redisTemplate, String key) {
      untilAsserted(
          () ->
              assertThat(redisTemplate.hasKey(key))
                  .as("Redis hash should be deleted: " + key)
                  .isFalse());
    }

    /**
     * Waits until all keys matching a pattern are deleted.
     *
     * @param redisTemplate The Redis template to query
     * @param pattern The key pattern to check (e.g., "{buffer:likes}:sync:*")
     */
    public void untilRedisKeysPatternAbsent(StringRedisTemplate redisTemplate, String pattern) {
      untilAsserted(
          () ->
              assertThat(redisTemplate.keys(pattern))
                  .as("No keys should match pattern: " + pattern)
                  .isEmpty());
    }

    /**
     * Waits until a boolean condition becomes true.
     *
     * @param condition Supplier that returns the condition to check
     * @param description Description of what is being waited for
     */
    public void untilTrue(Supplier<Boolean> condition, String description) {
      untilAsserted(() -> assertThat(condition.get()).as(description).isTrue());
    }

    /**
     * Waits until a boolean condition becomes false.
     *
     * @param condition Supplier that returns the condition to check
     * @param description Description of what is being waited for
     */
    public void untilFalse(Supplier<Boolean> condition, String description) {
      untilAsserted(() -> assertThat(condition.get()).as(description).isFalse());
    }

    /**
     * Executes the given assertion repeatedly until it passes or timeout expires.
     *
     * @param assertion The assertion to execute
     */
    public void untilAsserted(Runnable assertion) {
      conditionFactory.untilAsserted(() -> assertion.run());
    }
  }
}
