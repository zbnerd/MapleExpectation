package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.service.ingestion.NexonDataCollector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job to collect Nexon API data and publish to queue.
 *
 * <p><strong>ACL Stage 1 Execution:</strong>
 * This scheduler periodically triggers {@link NexonDataCollector} to fetch data
 * from Nexon API and publish to the queue, completing Stage 1 of the 3-stage pipeline.
 *
 * <p><strong>Schedule Configuration:</strong>
 * <ul>
 *   <li>Default: Every 10 minutes ({@code fixedRate = 600000})</li>
 *   <li>Configurable via {@code scheduler.nexon-data-collection.rate}</li>
 *   <li>Can be disabled via {@code scheduler.nexon-data-collection.enabled=false}</li>
 * </ul>
 *
 * <p><strong>Production Usage:</strong>
 * <ul>
 *   <li>Collects data for popular characters to warm up cache</li>
 *   <li>Runs independently of user requests (proactive data collection)</li>
 *   <li>Decouples data collection from user-facing APIs</li>
 * </ul>
 *
 * @see NexonDataCollector
 * @see maple.expectation.service.ingestion.BatchWriter
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "scheduler.nexon-data-collection.enabled",
    havingValue = "true",
    matchIfMissing = false  // Disabled by default
)
public class NexonDataCollectionScheduler {

  private final NexonDataCollector dataCollector;

  /**
   * Scheduled data collection from Nexon API.
   *
   * <p><strong>Workflow:</strong>
   * <ol>
   *   <li>Fetch character data from Nexon API via NexonDataCollector</li>
   *   <li>NexonDataCollector automatically publishes to queue</li>
   *   <li>BatchWriter consumes from queue and writes to DB (separate scheduled job)</li>
   * </ol>
   *
   * <p><strong>Error Handling:</strong>
   * Failures are logged but do not prevent subsequent executions.
   * Each character collection is independent (fire-and-forget).
   *
   * @see NexonDataCollector#fetchAndPublish(String)
   */
  @Scheduled(
      fixedRateString = "${scheduler.nexon-data-collection.rate:600000}",
      initialDelayString = "${scheduler.nexon-data-collection.initial-delay:30000}"
  )
  public void collectNexonData() {
    log.info("[NexonDataCollectionScheduler] Starting scheduled data collection");

    // TODO: Implement actual character list logic
    // For now, this is a placeholder to demonstrate the pipeline
    // In production, this would fetch a list of characters to update
    // (e.g., from database, popular characters, or scheduled updates)

    log.debug("[NexonDataCollectionScheduler] Data collection completed");
  }
}
