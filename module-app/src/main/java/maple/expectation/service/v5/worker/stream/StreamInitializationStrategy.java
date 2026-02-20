package maple.expectation.service.v5.worker.stream;

import org.redisson.api.RStream;

/**
 * Strategy interface for Redis Stream initialization.
 *
 * <h3>Three Initialization Cases:</h3>
 *
 * <ul>
 *   <li>New stream (no stream exists): Create group with StreamMessageId.NEWEST ($) for real-time
 *       mode
 *   <li>Existing stream without group: Create group with StreamMessageId.ALL (0) for backfill mode
 *   <li>Existing stream with group: No action needed
 * </ul>
 *
 * <h3>SOLID Compliance:</h3>
 *
 * <ul>
 *   <li>SRP: Each strategy handles one specific initialization scenario
 *   <li>OCP: New strategies can be added without modifying existing code
 *   <li>DIP: MongoDBSyncWorker depends on abstraction (interface), not concrete implementations
 * </ul>
 */
public interface StreamInitializationStrategy {

  /**
   * Initialize the stream according to the specific strategy.
   *
   * @param stream the Redis stream to initialize
   * @return true if initialization was performed, false if no action was needed
   */
  boolean initialize(RStream<String, String> stream);

  /**
   * Get a description of this strategy for logging purposes.
   *
   * @return strategy description
   */
  String getDescription();
}
