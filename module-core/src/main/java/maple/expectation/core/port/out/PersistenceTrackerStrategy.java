package maple.expectation.core.port.out;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Persistence Tracker Strategy Interface (#271 V5 Stateless Architecture)
 *
 * <h3>Role</h3>
 *
 * <p>Defines the strategy for tracking async persistence operations. Supports pluggable
 * implementations: In-Memory or Redis via Feature Flag.
 *
 * <h3>Implementations</h3>
 *
 * <ul>
 *   <li>maple.expectation.service.v2.shutdown.EquipmentPersistenceTracker - In-Memory
 *   <li>maple.expectation.infrastructure.queue.persistence.RedisEquipmentPersistenceTracker - Redis
 * </ul>
 *
 * <h3>5-Agent Council Agreement</h3>
 *
 * <ul>
 *   <li>Blue (Architect): Strategy pattern minimizes infrastructure changes
 *   <li>Green (Performance): In-Memory faster for single instance
 *   <li>Red (SRE): Feature flag enables operational rollback
 *   <li>Yellow (QA): In-Memory for tests removes external dependencies
 * </ul>
 *
 * @see maple.expectation.service.v2.shutdown.EquipmentPersistenceTracker In-Memory implementation
 * @see maple.expectation.infrastructure.queue.persistence.RedisEquipmentPersistenceTracker Redis
 *     implementation
 */
public interface PersistenceTrackerStrategy {

  /**
   * Register async persistence operation for tracking
   *
   * @param ocid character OCID
   * @param future async operation Future
   * @throws IllegalStateException if shutdown in progress
   */
  void trackOperation(String ocid, CompletableFuture<Void> future);

  /**
   * Wait for all operations to complete (for shutdown)
   *
   * @param timeout max wait time
   * @return true: all completed, false: timeout or already shutdown
   */
  boolean awaitAllCompletion(Duration timeout);

  /**
   * Get pending OCID list
   *
   * @return pending OCID list
   */
  List<String> getPendingOcids();

  /**
   * Get pending operation count
   *
   * @return pending operation count
   */
  int getPendingCount();

  /** Reset for testing */
  void resetForTesting();

  /**
   * Get current strategy type
   *
   * @return strategy type (IN_MEMORY or REDIS)
   */
  StrategyType getType();

  /** Persistence tracker strategy type */
  enum StrategyType {
    /** In-Memory ConcurrentHashMap based (single instance) */
    IN_MEMORY,
    /** Redis SET based (scale-out enabled) */
    REDIS
  }
}
