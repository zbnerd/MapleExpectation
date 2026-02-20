package maple.expectation.service.v5.worker.stream;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.redisson.api.RStream;
import org.redisson.api.stream.StreamReadGroupArgs;

/**
 * Factory for selecting the appropriate StreamInitializationStrategy.
 *
 * <h3>Strategy Selection Logic:</h3>
 *
 * <ul>
 *   <li>Stream doesn't exist → NewStreamStrategy (create with ID=$)
 *   <li>Stream exists, group doesn't exist → BackfillStrategy (create with ID=0)
 *   <li>Stream exists, group exists → NoOpStrategy
 * </ul>
 *
 * <h3>SOLID Compliance:</h3>
 *
 * <ul>
 *   <li>SRP: Single responsibility - strategy selection
 *   <li>OCP: Closed for modification, open for extension (add new strategies)
 *   <li>DIP: Depends on StreamInitializationStrategy abstraction
 * </ul>
 */
@Slf4j
public class StreamStrategyFactory {

  private static final String CONSUMER_GROUP = "mongodb-sync-group";
  private static final String CONSUMER_NAME = "mongodb-sync-worker";
  private static final Duration GROUP_CHECK_TIMEOUT = Duration.ofMillis(100);

  private final LogicExecutor executor;

  public StreamStrategyFactory(LogicExecutor executor) {
    this.executor = executor;
  }

  /**
   * Determine and return the appropriate strategy based on stream/group state.
   *
   * @param stream the Redis stream to evaluate
   * @return the appropriate StreamInitializationStrategy
   */
  public StreamInitializationStrategy determineStrategy(RStream<String, String> stream) {
    return executor.executeOrDefault(
        () -> {
          // Case 1: Stream doesn't exist
          if (!stream.isExists()) {
            log.debug("[StreamStrategyFactory] Stream does not exist -> NewStreamStrategy");
            return new NewStreamStrategy(executor);
          }

          // Stream exists - check if consumer group exists
          boolean groupExists = checkGroupExists(stream);

          // Case 2: Stream exists, group doesn't exist
          if (!groupExists) {
            log.debug("[StreamStrategyFactory] Stream exists, group missing -> BackfillStrategy");
            return new BackfillStrategy(executor);
          }

          // Case 3: Stream exists, group exists
          log.debug("[StreamStrategyFactory] Stream and group exist -> NoOpStrategy");
          return new NoOpStrategy(executor);
        },
        new NoOpStrategy(executor), // Fallback
        TaskContext.of("StreamStrategyFactory", "DetermineStrategy"));
  }

  /**
   * Check if consumer group exists by attempting a read operation.
   *
   * <p>This is a safe check that doesn't consume messages:
   *
   * <ul>
   *   <li>Uses neverDelivered() to only get new messages
   *   <li>Uses count(1) to minimize impact
   *   <li>Uses short timeout (100ms) to avoid blocking
   * </ul>
   *
   * @param stream the Redis stream to check
   * @return true if group exists, false otherwise
   */
  private boolean checkGroupExists(RStream<String, String> stream) {
    return executor.executeOrDefault(
        () -> {
          try {
            stream.readGroup(
                CONSUMER_GROUP,
                CONSUMER_NAME,
                StreamReadGroupArgs.neverDelivered().count(1).timeout(GROUP_CHECK_TIMEOUT));
            // If we get here, group exists
            log.debug(
                "[StreamStrategyFactory] Consumer group {} exists (readGroup succeeded)",
                CONSUMER_GROUP);
            return true;
          } catch (Exception e) {
            // Check for NOGROUP error
            if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
              log.debug(
                  "[StreamStrategyFactory] Consumer group {} does not exist (NOGROUP error)",
                  CONSUMER_GROUP);
              return false;
            }
            // Other exceptions - log and assume group doesn't exist
            log.warn(
                "[StreamStrategyFactory] Unexpected error checking group existence: {}",
                e.getMessage());
            return false;
          }
        },
        false, // Default: assume group doesn't exist on error
        TaskContext.of("StreamStrategyFactory", "CheckGroupExists"));
  }
}
