package maple.expectation.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Event Consumer Configuration - Priority-based thread pool separation.
 *
 * <h4>Design Intent:</h4>
 *
 * <ul>
 *   <li><b>Priority Isolation:</b> Separate virtual thread pools for HIGH/LOW priority events
 *   <li><b>Backpressure:</b> Semaphore limits prevent resource exhaustion
 *   <li><b>Virtual Threads:</b> Java 21 Loom for high-concurrency I/O-bound processing
 * </ul>
 *
 * <h4>Configuration (application.yml):</h4>
 *
 * <pre>{@code
 * event:
 *   consumer:
 *     high:
 *       max-concurrent: 50  # Default: 50 concurrent high-priority events
 *     low:
 *       max-concurrent: 20  # Default: 20 concurrent low-priority events
 * }</pre>
 *
 * <h4>SOLID Compliance:</h4>
 *
 * <ul>
 *   <li><b>SRP:</b> Single responsibility - event consumer configuration
 *   <li><b>OCP:</b> Open for extension (properties), closed for modification
 * </ul>
 *
 * @since 1.0.0
 * @see maple.expectation.event.HighPriorityEventConsumer
 * @see maple.expectation.event.LowPriorityEventConsumer
 */
@Slf4j
@Configuration
public class EventConsumerConfig {

  /**
   * Properties for high-priority event consumer.
   *
   * <p>Externalized configuration via {@code event.consumer.high.*} prefix.
   */
  @ConfigurationProperties(prefix = "event.consumer.high")
  public record HighPriorityConsumerProperties(@Name("max-concurrent") int maxConcurrent) {
    public HighPriorityConsumerProperties {
      if (maxConcurrent <= 0) {
        throw new IllegalArgumentException("max-concurrent must be positive: " + maxConcurrent);
      }
    }

    /** Default values */
    public static HighPriorityConsumerProperties defaults() {
      return new HighPriorityConsumerProperties(50);
    }
  }

  /**
   * Properties for low-priority event consumer.
   *
   * <p>Externalized configuration via {@code event.consumer.low.*} prefix.
   */
  @ConfigurationProperties(prefix = "event.consumer.low")
  public record LowPriorityConsumerProperties(@Name("max-concurrent") int maxConcurrent) {
    public LowPriorityConsumerProperties {
      if (maxConcurrent <= 0) {
        throw new IllegalArgumentException("max-concurrent must be positive: " + maxConcurrent);
      }
    }

    /** Default values */
    public static LowPriorityConsumerProperties defaults() {
      return new LowPriorityConsumerProperties(20);
    }
  }

  /**
   * High-priority event executor with semaphore backpressure.
   *
   * <h4>Thread Pool:</h4>
   *
   * <ul>
   *   <li>Type: Virtual Thread Per Task Executor (Java 21)
   *   <li>Backpressure: Semaphore with tryAcquire(5s) timeout
   *   <li>Rejection: Fail-fast with RejectedExecutionException
   * </ul>
   *
   * @param meterRegistry Micrometer registry
   * @param props Consumer properties from YAML
   * @return Wrapped executor with semaphore control
   */
  @Bean(name = "highPriorityEventExecutor")
  public Executor highPriorityEventExecutor(
      MeterRegistry meterRegistry, HighPriorityConsumerProperties props) {

    Semaphore semaphore = new Semaphore(props.maxConcurrent());
    Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    return runnable -> {
      boolean acquired = false;
      try {
        acquired = semaphore.tryAcquire(5, TimeUnit.SECONDS);
        if (!acquired) {
          meterRegistry.counter("event.consumer.high.rejected").increment();
          log.warn(
              "[HighPriorityExecutor] Semaphore timeout - concurrent limit reached (limit={})",
              props.maxConcurrent());
          throw new RejectedExecutionException("High priority event semaphore timeout");
        }
        virtualThreadExecutor.execute(runnable);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RejectedExecutionException("High priority event executor interrupted", e);
      } finally {
        if (acquired) {
          semaphore.release();
        }
      }
    };
  }

  /**
   * Low-priority event executor with semaphore backpressure.
   *
   * <h4>Thread Pool:</h4>
   *
   * <ul>
   *   <li>Type: Virtual Thread Per Task Executor (Java 21)
   *   <li>Backpressure: Semaphore with tryAcquire(5s) timeout
   *   <li>Rejection: Fail-fast with RejectedExecutionException
   * </ul>
   *
   * @param meterRegistry Micrometer registry
   * @param props Consumer properties from YAML
   * @return Wrapped executor with semaphore control
   */
  @Bean(name = "lowPriorityEventExecutor")
  public Executor lowPriorityEventExecutor(
      MeterRegistry meterRegistry, LowPriorityConsumerProperties props) {

    Semaphore semaphore = new Semaphore(props.maxConcurrent());
    Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    return runnable -> {
      boolean acquired = false;
      try {
        acquired = semaphore.tryAcquire(5, TimeUnit.SECONDS);
        if (!acquired) {
          meterRegistry.counter("event.consumer.low.rejected").increment();
          log.warn(
              "[LowPriorityExecutor] Semaphore timeout - concurrent limit reached (limit={})",
              props.maxConcurrent());
          throw new RejectedExecutionException("Low priority event semaphore timeout");
        }
        virtualThreadExecutor.execute(runnable);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RejectedExecutionException("Low priority event executor interrupted", e);
      } finally {
        if (acquired) {
          semaphore.release();
        }
      }
    };
  }

  /**
   * High-priority consumer properties bean.
   *
   * @return Properties with defaults applied
   */
  @Bean
  public HighPriorityConsumerProperties highPriorityConsumerProperties() {
    return HighPriorityConsumerProperties.defaults();
  }

  /**
   * Low-priority consumer properties bean.
   *
   * @return Properties with defaults applied
   */
  @Bean
  public LowPriorityConsumerProperties lowPriorityConsumerProperties() {
    return LowPriorityConsumerProperties.defaults();
  }
}
