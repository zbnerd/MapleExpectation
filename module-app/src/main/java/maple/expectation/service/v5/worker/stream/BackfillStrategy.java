package maple.expectation.service.v5.worker.stream;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.redisson.api.RStream;
import org.redisson.api.stream.StreamCreateGroupArgs;

/**
 * Strategy for initializing a consumer group on an existing Redis Stream.
 *
 * <h3>Behavior:</h3>
 *
 * <ul>
 *   <li>Stream already exists (has historical messages)
 *   <li>Creates consumer group with StreamMessageId.ALL (0) as starting ID
 *   <li>Consumer group will receive ALL existing messages (backfill mode)
 * </ul>
 *
 * <h3>Use Case:</h3>
 *
 * Consumer group creation after stream already has data, ensuring no messages are missed.
 */
@Slf4j
public class BackfillStrategy implements StreamInitializationStrategy {

  private static final String CONSUMER_GROUP = "mongodb-sync-group";

  private final LogicExecutor executor;

  public BackfillStrategy(LogicExecutor executor) {
    this.executor = executor;
  }

  @Override
  public boolean initialize(RStream<String, String> stream) {
    executor.executeVoid(
        () -> {
          stream.createGroup(StreamCreateGroupArgs.name(CONSUMER_GROUP).makeStream());
          long streamSize = stream.size();
          log.info(
              "[BackfillStrategy] Created consumer group for existing stream: {} "
                  + "(backfill mode, ID=0, streamSize={})",
              CONSUMER_GROUP,
              streamSize);
        },
        TaskContext.of("BackfillStrategy", "CreateGroupForExistingStream"));
    return true;
  }

  @Override
  public String getDescription() {
    return "Existing stream without group - create with ID=0 for backfill";
  }
}
