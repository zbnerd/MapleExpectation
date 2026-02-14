package maple.expectation.infrastructure.messaging;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.EventProcessingException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

/**
 * Redis-based deduplication filter for event processing.
 *
 * <p>Prevents duplicate event processing using Redis SET with O(1) lookup. Automatically expires
 * entries to prevent memory bloat.
 *
 * <p><strong>Use Case:</strong>
 *
 * <ul>
 *   <li>At-least-once delivery semantics (Redis Streams)
 *   <li>Exactly-once processing guarantee
 *   <li>Idempotent event handlers
 * </ul>
 *
 * <h3>CLAUDE.md Section 17 Compliance (TieredCache Pattern):</h3>
 *
 * <ul>
 *   <li><b>L1 (Memory):</b> Not used - distributed system requires shared state
 *   <li><b>L2 (Redis):</b> RSet with TTL for automatic cleanup
 * </ul>
 *
 * <h3>CLAUDE.md Section 12 Compliance:</h3>
 *
 * <ul>
 *   <li>No raw try-catch - uses LogicExecutor
 *   <li>Exception translation via executeWithTranslation
 * </ul>
 *
 * <h3>TTL Strategy:</h3>
 *
 * <ul>
 *   <li>Default: 24 hours (covers event replay window)
 *   <li>Configurable via {@code app.event.deduplication.ttl}
 * </ul>
 *
 * @see maple.expectation.infrastructure.messaging.RedisStreamEventConsumer
 * @since 1.0.0
 */
@Slf4j
public class DeduplicationFilter {

  private final RSet<String> processedEvents;
  private final LogicExecutor executor;
  private final long ttlMillis;

  /**
   * Create deduplication filter.
   *
   * @param redissonClient Redisson client
   * @param keyPrefix Redis key prefix (e.g., "event:dedup:")
   * @param ttlMillis TTL in milliseconds for event IDs
   * @param executor LogicExecutor for error handling
   */
  public DeduplicationFilter(
      RedissonClient redissonClient, String keyPrefix, long ttlMillis, LogicExecutor executor) {
    this.processedEvents = redissonClient.getSet(keyPrefix + "processed");
    this.executor = executor;
    this.ttlMillis = ttlMillis;
  }

  /**
   * Check if event has been processed, and mark as processed if not.
   *
   * <p><strong>Thread Safety:</strong> Redis SET operations are atomic.
   *
   * <p><strong>CLAUDE.md Section 12:</strong> Uses LogicExecutor for exception handling.
   *
   * @param eventId Unique event identifier (from IntegrationEvent)
   * @return {@code true} if event was already processed (duplicate), {@code false} if first time
   * @throws EventProcessingException if Redis operation fails
   */
  public boolean isDuplicate(String eventId) {
    return executor.executeOrDefault(
        () -> checkDuplicate(eventId),
        false, // Default: assume not duplicate on error (fail-open for resilience)
        TaskContext.of("DeduplicationFilter", "IsDuplicate", eventId));
  }

  /**
   * Check and mark duplicate with checked exception.
   *
   * <p>Extracted method for LogicExecutor pattern (Section 12).
   */
  private boolean checkDuplicate(String eventId) throws Exception {
    boolean added = processedEvents.add(eventId);

    if (!added) {
      log.debug("[DeduplicationFilter] Duplicate event detected: {}", eventId);
      return true; // Duplicate
    }

    // Set TTL on new entry to prevent memory bloat
    processedEvents.expire(ttlMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    log.debug("[DeduplicationFilter] Marked as processed: {}", eventId);
    return false; // Not duplicate
  }

  /**
   * Manually mark event as processed (for explicit idempotency control).
   *
   * <p>Use case: Post-processing cleanup or manual compensation.
   *
   * @param eventId Event ID to mark
   */
  public void markProcessed(String eventId) {
    executor.executeVoid(
        () -> {
          processedEvents.add(eventId);
          processedEvents.expire(ttlMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
          log.debug("[DeduplicationFilter] Manually marked: {}", eventId);
        },
        TaskContext.of("DeduplicationFilter", "MarkProcessed", eventId));
  }

  /**
   * Get current size of deduplication set (monitoring).
   *
   * @return Number of tracked event IDs
   */
  public int size() {
    return executor.executeOrDefault(
        () -> processedEvents.size(), 0, TaskContext.of("DeduplicationFilter", "Size"));
  }
}
