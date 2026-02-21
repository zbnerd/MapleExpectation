package maple.expectation.batch.writer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.service.v5.queue.PriorityCalculationQueue;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * Spring Batch ItemWriter for adding LOW priority tasks to PriorityCalculationQueue.
 *
 * <h3>Functionality</h3>
 *
 * <ul>
 *   <li>Writes OCID strings as LOW priority tasks to the queue
 *   <li>Records Micrometer metrics for queued and rejected tasks
 *   <li>Handles backpressure when queue is full
 * </ul>
 *
 * <h3>CLAUDE.md Compliance</h3>
 *
 * <ul>
 *   <li>Section 12: LogicExecutor pattern for exception handling
 *   <li>Section 15: Lambda limit - extracted private methods for 3+ line logic
 *   <li>Stateless: No mutable instance state
 * </ul>
 *
 * @see PriorityCalculationQueue
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LowPriorityQueueWriter implements ItemWriter<String> {

  private final PriorityCalculationQueue queue;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;

  private static final String METRICS_QUEUED = "batch.equipment.queued";
  private static final String METRICS_REJECTED = "batch.equipment.rejected";

  @Override
  public void write(Chunk<? extends String> ocids) {
    TaskContext context = TaskContext.of("Batch", "LowPriorityQueueWriter", "write");

    executor.executeVoid(
        () -> {
          AtomicInteger queuedCount = new AtomicInteger(0);
          AtomicInteger rejectedCount = new AtomicInteger(0);

          for (String ocid : ocids) {
            processSingleOcid(ocid, queuedCount, rejectedCount);
          }

          logWriteSummary(queuedCount.get(), rejectedCount.get());
        },
        context);
  }

  /**
   * Process a single OCID by adding it to the LOW priority queue.
   *
   * <p>Section 15 compliance: Lambda extraction for 3+ line logic with metrics recording.
   */
  private void processSingleOcid(
      String ocid, AtomicInteger queuedCount, AtomicInteger rejectedCount) {
    boolean accepted = queue.addLowPriorityTask(ocid);

    if (accepted) {
      recordQueued(ocid);
      queuedCount.incrementAndGet();
    } else {
      recordRejected(ocid);
      rejectedCount.incrementAndGet();
    }
  }

  /** Record successful queue addition metric. */
  private void recordQueued(String ocid) {
    Counter.builder(METRICS_QUEUED)
        .tag("priority", "low")
        .description("Number of tasks successfully queued for batch equipment refresh")
        .register(meterRegistry)
        .increment();
  }

  /** Record queue rejection metric (backpressure). */
  private void recordRejected(String ocid) {
    Counter.builder(METRICS_REJECTED)
        .tag("priority", "low")
        .tag("reason", "queue_full")
        .description("Number of tasks rejected due to queue backpressure")
        .register(meterRegistry)
        .increment();

    log.warn("[LowPriorityQueueWriter] Queue full, rejecting OCID: {}", ocid);
  }

  /** Log write operation summary. */
  private void logWriteSummary(int queuedCount, int rejectedCount) {
    log.info(
        "[LowPriorityQueueWriter] Write complete - queued: {}, rejected: {}",
        queuedCount,
        rejectedCount);
  }
}
