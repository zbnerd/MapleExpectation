package maple.expectation.service.v2.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.queue.like.RedisLikeRelationBuffer;
import org.redisson.api.RedissonClient;

/**
 * Adapter wrapper for RedisLikeRelationBuffer to implement v2 interface.
 *
 * <p>This adapter bridges the core port interface to the deprecated v2 interface for backward
 * compatibility.
 *
 * <p><b>Phase 3 Technical Debt:</b> This adapter can be removed once all services are migrated to
 * use the core port interface directly.
 */
public class RedisLikeRelationBufferAdapter implements LikeRelationBufferStrategy {

  private final RedisLikeRelationBuffer delegate;

  public RedisLikeRelationBufferAdapter(
      RedissonClient redissonClient, LogicExecutor executor, MeterRegistry meterRegistry) {
    this.delegate = new RedisLikeRelationBuffer(redissonClient, executor, meterRegistry);
  }

  @Override
  public StrategyType getType() {
    return delegate.getType();
  }

  @Override
  public Boolean addRelation(String accountId, String targetOcid) {
    return delegate.addRelation(accountId, targetOcid);
  }

  @Override
  public Boolean exists(String accountId, String targetOcid) {
    return delegate.exists(accountId, targetOcid);
  }

  @Override
  public String buildRelationKey(String accountId, String targetOcid) {
    return delegate.buildRelationKey(accountId, targetOcid);
  }

  @Override
  public String[] parseRelationKey(String relationKey) {
    return delegate.parseRelationKey(relationKey);
  }

  @Override
  public Boolean removeRelation(String accountId, String targetOcid) {
    return delegate.removeRelation(accountId, targetOcid);
  }

  @Override
  public Set<String> fetchAndRemovePending(int limit) {
    return delegate.fetchAndRemovePending(limit);
  }

  @Override
  public int getRelationsSize() {
    return delegate.getRelationsSize();
  }

  @Override
  public int getPendingSize() {
    return delegate.getPendingSize();
  }
}
