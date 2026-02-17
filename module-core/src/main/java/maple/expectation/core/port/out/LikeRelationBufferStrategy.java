package maple.expectation.core.port.out;

import java.util.Set;

/**
 * Like Relation Buffer Strategy Interface (#271 V5 Stateless Architecture)
 *
 * <h3>Role</h3>
 *
 * <p>Defines the strategy for buffering like relations. Supports pluggable implementations:
 * In-Memory or Redis via Feature Flag.
 *
 * <h3>Feature Flag</h3>
 *
 * <pre>
 * app.buffer.redis.enabled=true  → RedisLikeRelationBuffer
 * app.buffer.redis.enabled=false → LikeRelationBuffer (default)
 * </pre>
 *
 * <h3>Implementations</h3>
 *
 * <ul>
 *   <li>maple.expectation.service.v2.cache.LikeRelationBuffer - In-Memory + Redis hybrid
 *   <li>maple.expectation.infrastructure.queue.like.RedisLikeRelationBuffer - Redis Only
 * </ul>
 *
 * <h3>5-Agent Council Agreement</h3>
 *
 * <ul>
 *   <li>Blue (Architect): Strategy pattern for OCP compliance
 *   <li>Green (Performance): Implementation-specific optimizations possible
 *   <li>Purple (Auditor): Interface contract guarantees behavior
 * </ul>
 */
public interface LikeRelationBufferStrategy {

  /** Strategy type enum */
  enum StrategyType {
    /** In-Memory + Redis hybrid (legacy) */
    IN_MEMORY,
    /** Redis Only (V5 Stateless) */
    REDIS
  }

  /** Get current strategy type */
  StrategyType getType();

  /**
   * Add like relation
   *
   * @param accountId account of user who liked
   * @param targetOcid target character OCID
   * @return true: newly added, false: duplicate, null: Redis failure
   */
  Boolean addRelation(String accountId, String targetOcid);

  /**
   * Check if like relation exists
   *
   * @param accountId account of user who liked
   * @param targetOcid target character OCID
   * @return true: exists, false: not exists, null: Redis failure
   */
  Boolean exists(String accountId, String targetOcid);

  /**
   * Remove like relation
   *
   * @param accountId account of user who liked
   * @param targetOcid target character OCID
   * @return true: removed, false: not exists
   */
  Boolean removeRelation(String accountId, String targetOcid);

  /**
   * Fetch and remove pending relations for DB sync (atomic)
   *
   * @param limit max fetch count
   * @return pending relation set (accountId:targetOcid format)
   */
  Set<String> fetchAndRemovePending(int limit);

  /** Build relation key Format: {accountId}:{targetOcid} */
  String buildRelationKey(String accountId, String targetOcid);

  /**
   * Parse relation key
   *
   * @return [accountId, targetOcid]
   */
  String[] parseRelationKey(String relationKey);

  /**
   * Check if relation exists in unliked set (explicit unlike tracking)
   *
   * <p>Tracks explicitly unliked relations to distinguish cold start (Redis empty) from actual
   * unlike.
   *
   * @param accountId account of user who liked
   * @param targetOcid target character OCID
   * @return true: explicitly unliked, false: no unlike record, null: Redis failure
   */
  default Boolean existsInUnliked(String accountId, String targetOcid) {
    return null;
  }

  /** Get total relation count */
  int getRelationsSize();

  /** Get pending relation count */
  int getPendingSize();
}
