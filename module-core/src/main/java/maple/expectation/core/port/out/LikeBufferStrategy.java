package maple.expectation.core.port.out;

import java.util.Map;

/**
 * Like Buffer Strategy Interface (#271 V5 Stateless Architecture)
 *
 * <h3>Role</h3>
 *
 * <p>Defines the strategy for buffering like increments. Supports pluggable implementations:
 * In-Memory (Caffeine) or Redis via Feature Flag.
 *
 * <h3>5-Agent Council Agreement</h3>
 *
 * <ul>
 *   <li>Blue (Architect): Strategy pattern for OCP compliance
 *   <li>Green (Performance): Interface abstraction minimizes overhead
 *   <li>Red (SRE): Feature flag enables runtime switching
 *   <li>Purple (Auditor): 100% API compatibility
 * </ul>
 *
 * <h3>Implementations</h3>
 *
 * <ul>
 *   <li>maple.expectation.service.v2.cache.LikeBufferStorage - In-Memory Caffeine
 *   <li>maple.expectation.infrastructure.queue.like.RedisLikeBufferStorage - Redis
 * </ul>
 *
 * @see maple.expectation.service.v2.cache.LikeBufferStorage In-Memory implementation
 * @see maple.expectation.infrastructure.queue.like.RedisLikeBufferStorage Redis implementation
 */
public interface LikeBufferStrategy {

  /**
   * Atomic like increment
   *
   * @param userIgn target user IGN
   * @param delta increment value (positive: like, negative: unlike)
   * @return value after increment, null on failure
   */
  Long increment(String userIgn, long delta);

  /**
   * Get current counter value
   *
   * @param userIgn target user IGN
   * @return current delta value, 0 if not exists, null on failure
   */
  Long get(String userIgn);

  /**
   * Get all counters (for flush)
   *
   * @return userIgn → delta map
   */
  Map<String, Long> getAllCounters();

  /**
   * Atomic fetch + clear (for flush)
   *
   * @param limit max fetch count
   * @return userIgn → delta map
   */
  Map<String, Long> fetchAndClear(int limit);

  /**
   * Get buffer size
   *
   * @return number of entries in buffer
   */
  int getBufferSize();

  /**
   * Get strategy type
   *
   * @return IN_MEMORY or REDIS
   */
  StrategyType getType();

  /** Strategy type enum */
  enum StrategyType {
    IN_MEMORY,
    REDIS
  }
}
