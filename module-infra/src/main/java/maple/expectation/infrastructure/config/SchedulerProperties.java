package maple.expectation.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Scheduler Thread Pool Properties
 *
 * <h2>설정</h2>
 *
 * <pre>{@code
 * scheduler:
 *   task-scheduler:
 *     pool-size: 3  # 기본값: 3
 * }</pre>
 *
 * @see SchedulerConfig
 */
@ConfigurationProperties(prefix = "scheduler.task-scheduler")
public record SchedulerProperties(
    @DefaultValue("3") int poolSize, @DefaultValue("60") int awaitTerminationSeconds) {

  public SchedulerProperties {
    if (poolSize <= 0) {
      throw new IllegalArgumentException(
          "scheduler.task-scheduler.pool-size must be positive, got: " + poolSize);
    }
    if (awaitTerminationSeconds <= 0) {
      throw new IllegalArgumentException("await-termination-seconds must be positive");
    }
  }
}
