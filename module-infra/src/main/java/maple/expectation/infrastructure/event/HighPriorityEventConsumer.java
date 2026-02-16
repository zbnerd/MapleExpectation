package maple.expectation.infrastructure.event;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * High priority event consumer with dedicated virtual thread pool.
 *
 * <h4>Design Intent:</h4>
 *
 * <ul>
 *   <li><b>Priority Isolation:</b> Separate thread pool prevents low-priority events from blocking
 *       critical events
 *   <li><b>Virtual Threads:</b> Java 21 Loom for high-concurrency I/O-bound processing
 *   <li><b>Backpressure:</b> Semaphore limits concurrent execution to prevent resource exhaustion
 * </ul>
 *
 * <h4>Processing Rules:</h4>
 *
 * <ul>
 *   <li>Events with {@link EventPriority#HIGH} are processed here
 *   <li>Rejected tasks fail fast (no queuing) - caller must handle fallback
 *   <li>Metrics track processed, rejected, and processing time
 * </ul>
 *
 * <h4>SOLID Compliance:</h4>
 *
 * <ul>
 *   <li><b>SRP:</b> Single responsibility - high-priority event consumption
 *   <li><b>DIP:</b> Depends on LogicExecutor abstraction, not concrete implementations
 * </ul>
 *
 * @since 1.0.0
 * @see LowPriorityEventConsumer
 */
@Slf4j
@Component
public class HighPriorityEventConsumer {

  private final Executor executor;
  private final LogicExecutor logicExecutor;
  private final MeterRegistry meterRegistry;
  private final Semaphore semaphore;
  private final int maxConcurrent;

  /**
   * Constructor with dependency injection.
   *
   * @param logicExecutor Logic executor for error handling and logging
   * @param meterRegistry Micrometer registry for metrics
   * @param maxConcurrent Maximum concurrent processing (from YAML)
   */
  public HighPriorityEventConsumer(
      LogicExecutor logicExecutor,
      MeterRegistry meterRegistry,
      @Value("${event.consumer.high.max-concurrent:50}") int maxConcurrent) {

    this.logicExecutor = logicExecutor;
    this.meterRegistry = meterRegistry;
    this.maxConcurrent = maxConcurrent;
    this.semaphore = new Semaphore(maxConcurrent);

    // Virtual Thread Per Task Executor (Java 21)
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Process high-priority event asynchronously.
   *
   * <p>Processing flow:
   *
   * <ol>
   *   <li>Acquire semaphore (backpressure)
   *   <li>Execute on virtual thread
   *   <li>Apply LogicExecutor error handling
   *   <li>Record metrics
   * </ol>
   *
   * @param event Event to process
   * @param handler Event-specific processing logic
   * @param <T> Event payload type
   * @throws RejectedExecutionException if semaphore acquisition fails
   */
  public <T> void processAsync(IntegrationEvent<T> event, EventHandler<T> handler) {
    boolean acquired = false;
    try {
      // Backpressure: limit concurrent execution
      acquired = semaphore.tryAcquire(5, TimeUnit.SECONDS);
      if (!acquired) {
        meterRegistry.counter("event.consumer.high.rejected").increment();
        log.warn(
            "[HighPriorityConsumer] Semaphore timeout - concurrent limit reached (limit={})",
            maxConcurrent);
        throw new RejectedExecutionException("High priority event semaphore timeout");
      }

      executor.execute(
          () ->
              logicExecutor.executeVoid(
                  () -> {
                    long start = System.nanoTime();
                    try {
                      handler.handle(event.getPayload());
                      meterRegistry.counter("event.consumer.high.processed").increment();
                    } finally {
                      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                      meterRegistry
                          .timer("event.consumer.high.duration")
                          .record(durationMs, TimeUnit.MILLISECONDS);
                    }
                  },
                  TaskContext.of("HighPriorityEvent", event.getEventType(), event.getEventId())));

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RejectedExecutionException("High priority event consumer interrupted", e);
    } finally {
      if (acquired) {
        semaphore.release();
      }
    }
  }

  /**
   * Event handler functional interface.
   *
   * @param <T> Event payload type
   */
  @FunctionalInterface
  public interface EventHandler<T> {
    void handle(T payload);
  }
}
