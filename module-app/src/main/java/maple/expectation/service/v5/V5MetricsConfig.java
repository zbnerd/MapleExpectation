package maple.expectation.service.v5;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.service.v5.queue.PriorityCalculationQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * V5 CQRS: Prometheus Metrics Configuration
 *
 * <h3>Metrics</h3>
 *
 * <ul>
 *   <li>mongodb.query.latency: MongoDB read latency
 *   <li>mongodb.query.miss: MongoDB cache miss count
 *   <li>calculation.queue.depth: Current queue size
 *   <li>calculation.queue.high.count: High priority task count
 *   <li>calculation.worker.processed: Total tasks processed
 *   <li>calculation.worker.errors: Calculation failures
 *   <li>sync.worker.lag: Time between event and MongoDB upsert
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
public class V5MetricsConfig {

  private final PriorityCalculationQueue queue;
  private final MeterRegistry meterRegistry;

  public void initMetrics() {
    // Queue Depth Gauge
    Gauge.builder("calculation.queue.depth", queue, PriorityCalculationQueue::size)
        .description("Current calculation queue depth")
        .tag("priority", "all")
        .register(meterRegistry);

    // High Priority Count Gauge
    Gauge.builder(
            "calculation.queue.high.count", queue, PriorityCalculationQueue::getHighPriorityCount)
        .description("High priority tasks in queue")
        .register(meterRegistry);

    // MongoDB Miss Counter
    Counter.builder("mongodb.query.miss")
        .description("MongoDB cache miss count")
        .tag("layer", "read")
        .register(meterRegistry);

    // Sync Lag Timer
    Timer.builder("sync.worker.lag")
        .description("Time from event to MongoDB upsert")
        .tag("component", "mongodb-sync")
        .register(meterRegistry);

    log.info("[V5-Metrics] Initialized Prometheus metrics");
  }
}
