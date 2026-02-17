package maple.expectation.service.v2.shutdown;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.queue.persistence.RedisEquipmentPersistenceTracker;
import org.redisson.api.RedissonClient;

/**
 * Adapter wrapper for RedisEquipmentPersistenceTracker to implement v2 interface.
 *
 * <p>This adapter bridges the core port interface to the deprecated v2 interface for backward
 * compatibility.
 *
 * <p><b>Phase 3 Technical Debt:</b> This adapter can be removed once all services are migrated to
 * use the core port interface directly.
 */
public class RedisEquipmentPersistenceTrackerAdapter implements PersistenceTrackerStrategy {

  private final RedisEquipmentPersistenceTracker delegate;

  public RedisEquipmentPersistenceTrackerAdapter(
      RedissonClient redissonClient, LogicExecutor executor, MeterRegistry meterRegistry) {
    this.delegate = new RedisEquipmentPersistenceTracker(redissonClient, executor, meterRegistry);
  }

  @Override
  public void trackOperation(String ocid, CompletableFuture<Void> future) {
    delegate.trackOperation(ocid, future);
  }

  @Override
  public boolean awaitAllCompletion(Duration timeout) {
    return delegate.awaitAllCompletion(timeout);
  }

  @Override
  public List<String> getPendingOcids() {
    return delegate.getPendingOcids();
  }

  @Override
  public int getPendingCount() {
    return delegate.getPendingCount();
  }

  @Override
  public void resetForTesting() {
    delegate.resetForTesting();
  }

  @Override
  public StrategyType getType() {
    return StrategyType.valueOf(delegate.getType().name());
  }
}
