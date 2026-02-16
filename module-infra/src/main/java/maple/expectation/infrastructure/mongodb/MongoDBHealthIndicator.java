package maple.expectation.infrastructure.mongodb;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: MongoDB Health Check Indicator
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Expose MongoDB connection health via Actuator
 *   <li>Check connectivity and index status
 *   <li>Integrated with /actuator/health endpoint
 * </ul>
 *
 * <h3>Activation</h3>
 *
 * Only active when v5.enabled=true
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
public class MongoDBHealthIndicator implements HealthIndicator {

  private final MongoDBConfig mongoConfig;

  @Override
  public Health health() {
    boolean isHealthy = mongoConfig.isHealthy();

    if (isHealthy) {
      return Health.up()
          .withDetail("database", "maple_expectation_v5")
          .withDetail("ttl_enabled", "24 hours")
          .withDetail("status", "connected")
          .build();
    } else {
      return Health.down()
          .withDetail("database", "maple_expectation_v5")
          .withDetail("status", "disconnected")
          .build();
    }
  }
}
