package maple.expectation.service.v5;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.service.v5.executor.PriorityCalculationExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * V5 CQRS: Worker Pool Configuration
 *
 * <p>Starts the calculation worker pool on application startup and manages graceful shutdown.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
public class V5Config {

  private final PriorityCalculationExecutor executor;

  public V5Config(PriorityCalculationExecutor executor) {
    this.executor = executor;
  }

  @jakarta.annotation.PostConstruct
  public void startWorkerPool() {
    executor.start();
    log.info("[V5-Config] Calculation worker pool started successfully");
  }

  @PreDestroy
  public void shutdownWorkerPool() {
    log.info("[V5-Config] Shutting down worker pool...");
    executor.stop();
  }
}
