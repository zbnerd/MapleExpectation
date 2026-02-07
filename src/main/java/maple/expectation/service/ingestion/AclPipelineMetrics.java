package maple.expectation.service.ingestion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Counter.Builder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Metrics collector for ACL Pipeline components (Issue #300).
 *
 * <p><strong>Tracked Metrics:</strong>
 * <ul>
 *   <li>NexonDataCollector: API calls, success/failure, latency</li>
 *   <li>MessageQueue: Queue size, publish/consume rates</li>
 *   <li>BatchWriter: Batch size, processing time, DB operations</li>
 * </ul>
 *
 * <p><strong>Integration with Prometheus:</strong>
 * All metrics are exposed via Actuator at {@code /actuator/prometheus} endpoint.
 *
 * <h3>Metric Naming Convention:</h3>
 * <pre>{@code
 * acl_collector_api_calls_total        - Counter
 * acl_collector_api_latency_seconds     - Timer
 * acl_queue_size                       - Gauge
 * acl_writer_batch_processed_total     - Counter
 * acl_writer_batch_processing_seconds  - Timer
 * }</pre>
 *
 * @see io.micrometer.core.instrument.MeterRegistry
 * @see NexonDataCollector
 * @see BatchWriter
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AclPipelineMetrics {

  private final MeterRegistry meterRegistry;

  // Counters
  private Counter apiCallCounter;
  private Counter apiSuccessCounter;
  private Counter apiFailureCounter;
  private Counter queuePublishCounter;
  private Counter queuePublishFailureCounter;
  private Counter batchProcessedCounter;
  private Counter batchUpsertCounter;

  // Timers
  private Timer apiLatencyTimer;
  private Timer batchProcessingTimer;

  // Gauges
  private final AtomicLong queueSize = new AtomicLong(0);

  /**
   * Initialize metrics on component startup.
   * Called by Spring after dependency injection.
   */
  public void init() {
    // NexonDataCollector metrics
    this.apiCallCounter = Counter.builder("acl_collector_api_calls_total")
        .description("Total number of Nexon API calls")
        .tag("component", "NexonDataCollector")
        .register(meterRegistry);

    this.apiSuccessCounter = Counter.builder("acl_collector_api_success_total")
        .description("Successful Nexon API calls")
        .tag("component", "NexonDataCollector")
        .tag("status", "success")
        .register(meterRegistry);

    this.apiFailureCounter = Counter.builder("acl_collector_api_failure_total")
        .description("Failed Nexon API calls")
        .tag("component", "NexonDataCollector")
        .tag("status", "failure")
        .register(meterRegistry);

    this.apiLatencyTimer = Timer.builder("acl_collector_api_latency_seconds")
        .description("Nexon API call latency")
        .tag("component", "NexonDataCollector")
        .publishPercentiles(0.5, 0.95, 0.99)
        .publishPercentileHistogram()
        .register(meterRegistry);

    // MessageQueue metrics
    this.queuePublishCounter = Counter.builder("acl_queue_publish_total")
        .description("Total messages published to queue")
        .tag("component", "RedisEventPublisher")
        .register(meterRegistry);

    this.queuePublishFailureCounter = Counter.builder("acl_queue_publish_failure_total")
        .description("Failed queue publish attempts")
        .tag("component", "RedisEventPublisher")
        .tag("status", "failure")
        .register(meterRegistry);

    // Queue size gauge (dynamic)
    Gauge.builder("acl_queue_size", queueSize, AtomicLong::get)
        .description("Current number of messages in queue")
        .tag("component", "MessageQueue")
        .register(meterRegistry);

    // BatchWriter metrics
    this.batchProcessedCounter = Counter.builder("acl_writer_batches_processed_total")
        .description("Total number of batches processed")
        .tag("component", "BatchWriter")
        .register(meterRegistry);

    this.batchUpsertCounter = Counter.builder("acl_writer_records_upserted_total")
        .description("Total number of records upserted via batch")
        .tag("component", "BatchWriter")
        .register(meterRegistry);

    this.batchProcessingTimer = Timer.builder("acl_writer_batch_processing_seconds")
        .description("Batch processing time")
        .tag("component", "BatchWriter")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(meterRegistry);

    log.info("[AclPipelineMetrics] Metrics initialized and registered with Prometheus");
  }

  // ========== NexonDataCollector Metrics ==========

  public void recordApiCall() {
    apiCallCounter.increment();
  }

  public void recordApiSuccess() {
    apiSuccessCounter.increment();
  }

  public void recordApiFailure() {
    apiFailureCounter.increment();
  }

  public Timer.Sample startApiLatency() {
    return Timer.start(meterRegistry);
  }

  public void recordApiLatency(Timer.Sample sample) {
    sample.stop(apiLatencyTimer);
  }

  // ========== MessageQueue Metrics ==========

  public void recordQueuePublish() {
    queuePublishCounter.increment();
  }

  public void recordQueuePublishFailure() {
    queuePublishFailureCounter.increment();
  }

  public void setQueueSize(long size) {
    queueSize.set(size);
  }

  public long getQueueSize() {
    return queueSize.get();
  }

  public void incrementQueueSize() {
    queueSize.incrementAndGet();
  }

  public void decrementQueueSize() {
    queueSize.decrementAndGet();
  }

  // ========== BatchWriter Metrics ==========

  public void recordBatchProcessed(int batchSize) {
    batchProcessedCounter.increment();
    batchUpsertCounter.increment(batchSize);
  }

  public Timer.Sample startBatchProcessing() {
    return Timer.start(meterRegistry);
  }

  public void recordBatchProcessing(Timer.Sample sample) {
    sample.stop(batchProcessingTimer);
  }
}
