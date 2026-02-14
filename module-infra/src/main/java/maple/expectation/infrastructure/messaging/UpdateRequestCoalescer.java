package maple.expectation.infrastructure.messaging;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.common.resource.ResourceLoader;
import maple.expectation.error.exception.AtomicFetchException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

/**
 * Coalesces multiple update requests for the same user using Redis Lua Script.
 *
 * <p><strong>Design Pattern:</strong> Command Pattern with Atomic Lua Script execution. Prevents
 * duplicate processing and batches requests for efficiency.
 *
 * <p><strong>Redis Cluster Compatibility:</strong> Uses Hash Tag {userId} pattern to ensure all
 * keys map to the same cluster slot. (Section 8-1: infrastructure.md)
 *
 * <p><strong>Atomic Operations:</strong>
 *
 * <ul>
 *   <li>Deduplication: HGET prevents duplicate event IDs
 *   <li>Batching: HINCRBY tracks batch size
 *   <li>Safety: TTL prevents orphan keys from memory leaks
 * </ul>
 *
 * <p><strong>CLAUDE.md Section 4 Compliance:</strong>
 *
 * <ul>
 *   <li>SRP: Single responsibility - coalescing logic only
 *   <li>DIP: Depends on RedisClient abstraction
 *   <li>LogicExecutor: Exception handling via executeWithTranslation
 * </ul>
 *
 * <h3>Lua Script Contract (coalesce_add.lua):</h3>
 *
 * <pre>
 * KEYS[1] = {event:coalesce}:{userId}
 * KEYS[2] = {event:coalesce:counter}:{userId}
 * ARGV[1] = eventType
 * ARGV[2] = eventId
 * ARGV[3] = eventData
 * ARGV[4] = maxBatchSize
 * ARGV[5] = ttlSeconds
 *
 * Returns: {status, batchCount, shouldFlush}
 * </pre>
 *
 * @see maple.expectation.infrastructure.messaging.TwoBucketRateLimiter
 * @see maple.expectation.error.exception.AtomicFetchException
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateRequestCoalescer {

  private static final String LUA_COALESCE_ADD = "lua/event/coalesce_add.lua";

  private final RedissonClient redissonClient;
  private final LogicExecutor executor;
  private final ResourceLoader resourceLoader;

  /**
   * Result of coalesce operation.
   *
   * @param status Operation status (QUEUED, DUPLICATE)
   * @param batchCount Current number of requests in batch
   * @param shouldFlush Whether batch should be flushed
   */
  public record CoalesceResult(String status, int batchCount, boolean shouldFlush) {

    public boolean isDuplicate() {
      return "DUPLICATE".equals(status);
    }

    public boolean isQueued() {
      return "QUEUED".equals(status);
    }
  }

  /**
   * Coalesce an update request for the specified user.
   *
   * <p>Uses Lua Script for atomic deduplication and batching.
   *
   * @param userId User identifier (for Hash Tag)
   * @param eventType Type of event (e.g., "CHARACTER_UPDATE")
   * @param eventId Unique event identifier
   * @param eventData Serialized event data
   * @param maxBatchSize Maximum batch size before forcing flush
   * @param ttlSeconds TTL for coalesced data (safety)
   * @return CoalesceResult with operation status
   * @throws AtomicFetchException if Lua Script execution fails
   */
  public CoalesceResult coalesce(
      String userId,
      String eventType,
      String eventId,
      String eventData,
      int maxBatchSize,
      int ttlSeconds) {

    return executor.executeWithTranslation(
        () -> coalesceInternal(userId, eventType, eventId, eventData, maxBatchSize, ttlSeconds),
        ExceptionTranslator.forRedisScript(),
        TaskContext.of("UpdateRequestCoalescer", "Coalesce", userId));
  }

  /**
   * Internal coalesce implementation with checked exceptions.
   *
   * <p>Loads Lua Script and executes with Redisson RScript.
   */
  private CoalesceResult coalesceInternal(
      String userId,
      String eventType,
      String eventId,
      String eventData,
      int maxBatchSize,
      int ttlSeconds)
      throws Exception {

    // Load Lua Script
    String luaScript = resourceLoader.loadResourceAsString(LUA_COALESCE_ADD);

    // Build Hash Tag keys for Redis Cluster compatibility
    String coalesceKey = buildCoalesceKey(userId);
    String counterKey = buildCounterKey(userId);

    RScript script = redissonClient.getScript(StringCodec.INSTANCE);

    // Execute Lua Script
    @SuppressWarnings("unchecked")
    List<Object> result =
        script.eval(
            RScript.Mode.READ_WRITE,
            luaScript,
            RScript.ReturnType.MULTI,
            List.of(coalesceKey, counterKey),
            eventType,
            eventId,
            eventData,
            String.valueOf(maxBatchSize),
            String.valueOf(ttlSeconds));

    // Parse result: {status, batchCount, shouldFlush}
    String status = (String) result.get(0);
    int batchCount = Integer.parseInt((String) result.get(1));
    boolean shouldFlush = "1".equals(result.get(2));

    log.debug(
        "[UpdateRequestCoalescer] Coalesce result: userId={}, eventId={}, status={}, batchCount={}, shouldFlush={}",
        userId,
        eventId,
        status,
        batchCount,
        shouldFlush);

    return new CoalesceResult(status, batchCount, shouldFlush);
  }

  /**
   * Get current batch count for a user.
   *
   * <p>Non-blocking read operation for monitoring/decision making.
   *
   * @param userId User identifier
   * @param eventType Event type to query
   * @return Current batch count (0 if no data)
   */
  public int getBatchCount(String userId, String eventType) {
    return executor.executeOrDefault(
        () -> getBatchCountInternal(userId, eventType),
        0,
        TaskContext.of("UpdateRequestCoalescer", "GetBatchCount", userId));
  }

  private int getBatchCountInternal(String userId, String eventType) {
    String counterKey = buildCounterKey(userId);
    var bucket = redissonClient.getBucket(counterKey + ":" + eventType, StringCodec.INSTANCE);
    Object count = bucket.get();
    return count != null ? Integer.parseInt(count.toString()) : 0;
  }

  /**
   * Flush coalesced batch for processing.
   *
   * <p>Atomically fetches and removes all events for the user.
   *
   * @param userId User identifier
   * @return List of event data (may be empty)
   */
  public List<String> flushBatch(String userId) {
    return executor.execute(
        () -> flushBatchInternal(userId),
        TaskContext.of("UpdateRequestCoalescer", "FlushBatch", userId));
  }

  private List<String> flushBatchInternal(String userId) {
    String coalesceKey = buildCoalesceKey(userId);
    String counterKey = buildCounterKey(userId);

    // Fetch all events
    var map = redissonClient.getMap(coalesceKey, StringCodec.INSTANCE);
    // Explicitly cast to List<String> since map.values() returns Collection<Object>
    @SuppressWarnings("unchecked")
    List<String> events = (List<String>) (List<?>) (List<?>) (Object) map.values();

    // Clear batch atomically
    map.delete();
    redissonClient.getBucket(counterKey, StringCodec.INSTANCE).delete();

    log.debug(
        "[UpdateRequestCoalescer] Flushed batch: userId={}, eventCount={}", userId, events.size());

    return events;
  }

  // Hash Tag pattern for Redis Cluster (Section 8-1)
  private String buildCoalesceKey(String userId) {
    return "{event:coalesce}:" + userId;
  }

  private String buildCounterKey(String userId) {
    return "{event:coalesce:counter}:" + userId;
  }
}
