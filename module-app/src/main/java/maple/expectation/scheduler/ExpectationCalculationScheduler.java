package maple.expectation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.repository.GameCharacterRepository;
import maple.expectation.service.v5.queue.PriorityCalculationQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * V5 CQRS: Expectation Calculation Scheduler
 *
 * <p>Periodically adds LOW priority tasks to refresh all users' expectation calculations.
 *
 * <h3>Flow</h3>
 *
 * <ol>
 *   <li>Fetch all user IGNs from database (paginated)
 *   <li>Add LOW priority task to queue for each user
 *   <li>Worker processes tasks in background
 * </ol>
 *
 * <h3>Scheduling Strategy</h3>
 *
 * <ul>
 *   <li>fixedDelay: Waits specified time AFTER previous execution completes
 *   <li>Prevents queue overload by respecting backpressure
 *   <li>Batch size configurable via application.yml
 * </ul>
 *
 * <h3>Use Cases</h3>
 *
 * <ul>
 *   <li>Nightly full recalculation of all users
 *   <li>Periodic data freshness maintenance
 *   <li>Background cache warming
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "scheduler.expectation-calculation.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class ExpectationCalculationScheduler {

  private final PriorityCalculationQueue queue;
  private final GameCharacterRepository gameCharacterRepository;
  private final LogicExecutor executor;

  /**
   * Add LOW priority tasks for all users
   *
   * <p>Configuration:
   *
   * <ul>
   *   <li>scheduler.expectation-calculation.fixed-delay-ms: Delay between executions (default:
   *       3600000 = 1 hour)
   *   <li>scheduler.expectation-calculation.batch-size: Users per batch (default: 100)
   * </ul>
   *
   * <h3>Backpressure Handling</h3>
   *
   * <p>If queue is full (MAX_QUEUE_SIZE = 10,000), LOW priority tasks are rejected gracefully
   * without error.
   */
  @Scheduled(fixedDelayString = "${scheduler.expectation-calculation.fixed-delay-ms:3600000}")
  public void refreshAllUsers() {
    TaskContext context = TaskContext.of("Scheduler", "ExpectationCalculation.RefreshAll");

    executor.executeVoid(
        () -> {
          log.info("[ExpectationCalculation] Starting full user refresh");
          int batchSize =
              Integer.parseInt(
                  "${scheduler.expectation-calculation.batch-size:100}"); // TODO: Load from
          // @Value configuration
          int processedCount = 0;
          int skippedCount = 0;

          // Paginated fetch to avoid memory issues
          final int pageSize = 100;
          int page = 0;
          boolean hasMore = true;

          while (hasMore) {
            final int currentPage = page;
            hasMore =
                executor.executeOrDefault(
                    () -> {
                      org.springframework.data.domain.PageRequest pageRequest =
                          org.springframework.data.domain.PageRequest.of(currentPage, pageSize);
                      return gameCharacterRepository.findAll(pageRequest).stream()
                          .map(character -> addTaskForUser(character.getUserIgn()))
                          .toList()
                          .contains(true);
                    },
                    false,
                    context);

            if (hasMore) {
              page++;
              processedCount += pageSize;

              // Log progress every 1000 users
              if (processedCount % 1000 == 0) {
                log.info(
                    "[ExpectationCalculation] Processed {} users, skipped {} (queue full)",
                    processedCount,
                    skippedCount);
              }
            }
          }

          log.info(
              "[ExpectationCalculation] Full refresh completed: processed={}, skipped={}, queueSize={}",
              processedCount,
              skippedCount,
              queue.size());
        },
        context);
  }

  /**
   * Add LOW priority task for a single user
   *
   * @return true if task was added, false if rejected (backpressure)
   */
  private boolean addTaskForUser(String userIgn) {
    return queue.addLowPriorityTask(userIgn);
  }
}
