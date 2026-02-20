package maple.expectation.service.v5.worker.stream;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.redisson.api.RStream;
import org.redisson.api.stream.StreamCreateGroupArgs;

/**
 * Strategy for initializing a new Redis Stream.
 *
 * <h3>Behavior:</h3>
 *
 * <ul>
 *   <li>Creates the stream and consumer group together
 *   <li>Uses StreamMessageId.NEWEST ($) as starting ID
 *   <li>Consumer group will only receive NEW messages after creation (real-time mode)
 * </ul>
 *
 * <h3>Use Case:</h3>
 *
 * Fresh deployment or when stream is intentionally reset.
 */
@Slf4j
public class NewStreamStrategy implements StreamInitializationStrategy {

  private static final String CONSUMER_GROUP = "mongodb-sync-group";

  private final LogicExecutor executor;

  public NewStreamStrategy(LogicExecutor executor) {
    this.executor = executor;
  }

  @Override
  public boolean initialize(RStream<String, String> stream) {
    executor.executeVoid(
        () -> {
          stream.createGroup(StreamCreateGroupArgs.name(CONSUMER_GROUP).makeStream());
          log.info(
              "[NewStreamStrategy] Created new stream and consumer group: {} (real-time mode, ID=$)",
              CONSUMER_GROUP);
        },
        TaskContext.of("NewStreamStrategy", "CreateStreamAndGroup"));
    return true;
  }

  @Override
  public String getDescription() {
    return "New stream - create with ID=$ for real-time consumption";
  }
}
