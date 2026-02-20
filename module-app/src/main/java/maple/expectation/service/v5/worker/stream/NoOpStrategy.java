package maple.expectation.service.v5.worker.stream;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.redisson.api.RStream;

/**
 * No-operation strategy for when stream and consumer group already exist.
 *
 * <h3>Behavior:</h3>
 *
 * <ul>
 *   <li>Stream exists
 *   <li>Consumer group exists
 *   <li>No initialization needed - consumer can proceed to read messages
 * </ul>
 *
 * <h3>Use Case:</h3>
 *
 * Normal restart scenario where infrastructure is already properly initialized.
 */
@Slf4j
public class NoOpStrategy implements StreamInitializationStrategy {

  private final LogicExecutor executor;

  public NoOpStrategy(LogicExecutor executor) {
    this.executor = executor;
  }

  @Override
  public boolean initialize(RStream<String, String> stream) {
    executor.executeVoid(
        () -> {
          long streamSize = stream.size();
          log.info(
              "[NoOpStrategy] Stream and consumer group already exist (streamSize={}). "
                  + "No initialization needed.",
              streamSize);
        },
        TaskContext.of("NoOpStrategy", "VerifyExistingGroup"));
    return false;
  }

  @Override
  public String getDescription() {
    return "Existing stream with group - no action needed";
  }
}
