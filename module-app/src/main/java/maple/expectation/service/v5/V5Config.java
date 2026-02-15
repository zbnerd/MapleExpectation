package maple.expectation.service.v5;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.service.v5.worker.ExpectationCalculationWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * V5 CQRS: Worker Thread Configuration
 *
 * <p>Starts the calculation worker thread on application startup.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
public class V5Config {

  private final ExpectationCalculationWorker worker;

  public V5Config(ExpectationCalculationWorker worker) {
    this.worker = worker;
  }

  @PostConstruct
  public void startWorker() {
    // Start worker in daemon thread
    Thread workerThread = new Thread(worker, "V5-CalculationWorker");
    workerThread.setDaemon(true);
    workerThread.setUncaughtExceptionHandler(
        (t, e) -> {
          log.error("[V5-Config] Worker thread crashed", e);
        });
    workerThread.start();

    // Shutdown hook for graceful termination
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  log.info("[V5-Config] Shutting down worker...");
                  workerThread.interrupt();
                  try {
                    workerThread.join(5000); // 5 seconds in milliseconds
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));

    log.info("[V5-Config] Calculation worker started successfully");
  }
}
