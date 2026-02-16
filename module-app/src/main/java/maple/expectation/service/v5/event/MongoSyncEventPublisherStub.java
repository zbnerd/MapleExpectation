package maple.expectation.service.v5.event;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stub implementation of MongoSyncEventPublisher for Command Side testing
 *
 * <p>This stub allows the Command Side to compile without Query Side dependencies. The real
 * implementation will be enabled when v5.query-side-enabled=true.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "v5.query-side-enabled", havingValue = "false", matchIfMissing = true)
public class MongoSyncEventPublisherStub implements MongoSyncEventPublisherInterface {

  private final LogicExecutor executor;

  public MongoSyncEventPublisherStub(LogicExecutor executor) {
    this.executor = executor;
    log.info("[V5-Stub] MongoSyncEventPublisher stub initialized (Query Side disabled)");
  }

  @Override
  public void publishCalculationCompleted(String taskId, EquipmentExpectationResponseV4 response) {
    TaskContext context = TaskContext.of("MongoSyncPublisherStub", "Publish", taskId);
    executor.executeVoid(
        () -> {
          log.debug(
              "[V5-Stub] Event publishing skipped (Query Side disabled): taskId={}, userIgn={}",
              taskId,
              response.getUserIgn());
        },
        context);
  }
}
