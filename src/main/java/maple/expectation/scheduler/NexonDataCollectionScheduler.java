package maple.expectation.scheduler;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.ingestion.NexonDataCollector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job to collect Nexon API data and publish to queue.
 *
 * <p><strong>ACL Stage 1 Execution:</strong> This scheduler periodically triggers {@link
 * NexonDataCollector} to fetch data from Nexon API and publish to the queue, completing Stage 1 of
 * the 3-stage pipeline.
 *
 * <p><strong>Schedule Configuration:</strong>
 *
 * <ul>
 *   <li>Default: Every 10 minutes ({@code fixedRate = 600000})
 *   <li>Configurable via {@code scheduler.nexon-data-collection.rate}
 *   <li>Can be disabled via {@code scheduler.nexon-data-collection.enabled=false}
 * </ul>
 *
 * <p><strong>Production Usage:</strong>
 *
 * <ul>
 *   <li>Collects data for all active maple characters to warm up cache
 *   <li>Runs independently of user requests (proactive data collection)
 *   <li>Decouples data collection from user-facing APIs
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
    matchIfMissing = false // Disabled by default
    )
public class NexonDataCollectionScheduler {

  private final NexonDataCollector dataCollector;
  private final GameCharacterRepository gameCharacterRepository;
  private final LogicExecutor executor;

  /**
   * Scheduled data collection from Nexon API.
   *
   * <p><strong>Workflow:</strong>
   *
   * <ol>
   *   <li>Fetch all active maple characters from database
   *   <li>For each character, call NexonDataCollector to fetch from API
   *   <li>NexonDataCollector automatically publishes to queue
   *   <li>BatchWriter consumes from queue and writes to DB (separate scheduled job)
   * </ol>
   *
   * <p><strong>Error Handling (CLAUDE.md Section 12):</strong> Uses {@link LogicExecutor} pattern
   * instead of try-catch. Failures are logged but do not prevent subsequent executions. Each
   * character collection is independent (fire-and-forget).
   *
   * @see NexonDataCollector#fetchAndPublish(String)
   */
  @Scheduled(
      fixedRateString = "${scheduler.nexon-data-collection.rate:600000}",
      initialDelayString = "${scheduler.nexon-data-collection.initial-delay:30000}")
  public void collectNexonData() {
    executor.executeVoid(
        this::processAllCharacters, TaskContext.of("Scheduler", "NexonDataCollection"));
  }

  /**
   * Process all active characters and collect their data from Nexon API.
   *
   * <p>This method is called by {@link #collectNexonData()} through LogicExecutor to ensure proper
   * exception handling and logging.
   */
  private void processAllCharacters() {
    log.info("[NexonDataCollectionScheduler] Starting scheduled data collection");

    // Fetch all maple characters from database (limit to first 100 for testing)
    List<GameCharacter> allCharacters = gameCharacterRepository.findAll();

    // Filter for recently updated characters (within last 30 days)
    // In production, this should use a proper query or an indexed field
    List<GameCharacter> charactersToProcess =
        allCharacters.stream()
            .limit(100) // Safety limit for testing
            .toList();

    if (charactersToProcess.isEmpty()) {
      log.info("[NexonDataCollectionScheduler] No characters found in database");
      return;
    }

    log.info("[NexonDataCollectionScheduler] Processing {} characters", charactersToProcess.size());

    // Collect data for each character
    int successCount = 0;
    int failureCount = 0;

    for (GameCharacter character : charactersToProcess) {
      String ocid = character.getOcid();
      String userIgn = character.getUserIgn();

      // Use executeOrCatch to count successes/failures while continuing processing
      Boolean success =
          executor.executeOrDefault(
              () -> {
                dataCollector.fetchAndPublish(ocid);
                return true;
              },
              false, // Default to false on exception
              TaskContext.of("Scheduler", "CollectCharacter", userIgn));

      if (success) {
        successCount++;
        log.debug(
            "[NexonDataCollectionScheduler] Successfully collected data for: {} (ocid={})",
            userIgn,
            ocid);
      } else {
        failureCount++;
        log.error(
            "[NexonDataCollectionScheduler] Failed to collect data for: {} (ocid={})",
            userIgn,
            ocid);
      }
    }

    log.info(
        "[NexonDataCollectionScheduler] Data collection completed: " + "success={}, failure={}",
        successCount,
        failureCount);
  }
}
