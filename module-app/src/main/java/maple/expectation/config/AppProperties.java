package maple.expectation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Application-wide properties configuration.
 *
 * <h3>V5 Stateless Architecture (#271) - Redis Mode Enforcement</h3>
 *
 * <p>This configuration enforces Redis-based distributed state for scale-out capability.
 *
 * <h3>Feature Flags</h3>
 *
 * <ul>
 *   <li>app.buffer.redis.enabled: Redis buffer mode (default: true, cannot be disabled in
 *       production)
 *   <li>app.instance-id: Unique instance identifier for scale-out coordination
 * </ul>
 *
 * <h3>Scale-out Requirements</h3>
 *
 * <p>In distributed multi-instance deployments, in-memory buffers cause data loss during
 * deployments/instance failures. This configuration enforces Redis mode to prevent stateful
 * components that block horizontal scaling.
 *
 * @see maple.expectation.infrastructure.queue.like.RedisLikeBufferStorage
 * @see maple.expectation.infrastructure.queue.strategy.RedisBufferStrategy
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

  /**
   * Buffer configuration (V5 Stateless)
   *
   * <p>Controls whether write-behind buffers use Redis (distributed) or in-memory storage.
   *
   * <p><b>Scale-out Requirement:</b> Redis mode must be enabled for production deployments to
   * prevent data loss during instance failures.
   *
   * @see maple.expectation.service.v2.cache.LikeBufferStorage In-Memory (single instance only)
   * @see maple.expectation.infrastructure.queue.like.RedisLikeBufferStorage Redis (scale-out ready)
   */
  private Buffer buffer = new Buffer();

  /**
   * Unique instance identifier for scale-out coordination.
   *
   * <p>Used for:
   *
   * <ul>
   *   <li>Distributed lock ownership tracking
   *   <li>Outbox message deduplication
   *   <li>Graceful shutdown coordination
   * </ul>
   */
  private String instanceId;

  @Data
  public static class Buffer {

    /**
     * Redis buffer mode enabled flag.
     *
     * <p>When true (default): All stateful buffers use Redis for distributed state management. This
     * is required for scale-out deployments.
     *
     * <p>When false: Buffers use in-memory storage (single instance only, data loss on failure).
     *
     * <p><b>Production Deployment:</b> This must remain true. Disabling Redis mode in production
     * will cause data loss during deployments/instance failures.
     */
    private boolean redisEnabled = true;
  }
}
