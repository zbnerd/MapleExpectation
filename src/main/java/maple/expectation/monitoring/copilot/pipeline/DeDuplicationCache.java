package maple.expectation.monitoring.copilot.pipeline;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * De-duplication cache for alert notifications.
 *
 * <p>Prevents alert spam by tracking recent incident IDs within a configurable time window. This
 * component follows the Single Responsibility Principle (SRP) by separating de-duplication logic
 * from notification logic.
 *
 * <h3>Throttling Strategy</h3>
 *
 * <p>Implements time-based de-duplication with configurable window (default 5 minutes):
 *
 * <ul>
 *   <li>Tracks recent incident IDs in memory
 *   <li>Cleaned up periodically to prevent memory leaks
 *   <li>Prevents duplicate alerts for same incident signature
 * </ul>
 *
 * <h3>Thread Safety</h3>
 *
 * <p>Uses ConcurrentHashMap for thread-safe operations in multi-threaded alert scenarios.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monitoring.copilot.enabled", havingValue = "true")
public class DeDuplicationCache {

  @Value("${monitoring.copilot.alert.throttle-window-ms:300000}")
  private long throttleWindowMs;

  /** Recent incident IDs with their timestamps (last N minutes by default) */
  private final ConcurrentHashMap<String, Long> recentIncidents = new ConcurrentHashMap<>();

  /**
   * Check if incident is recent (within throttle window).
   *
   * @param incidentId The incident ID to check
   * @param now Current timestamp in milliseconds
   * @return true if incident was recently tracked, false otherwise
   */
  public boolean isRecent(String incidentId, long now) {
    Long timestamp = recentIncidents.get(incidentId);
    if (timestamp == null) {
      return false;
    }

    long age = now - timestamp;
    return age < throttleWindowMs;
  }

  /**
   * Track incident to prevent duplicates.
   *
   * @param incidentId The incident ID to track
   * @param timestamp The timestamp when incident occurred
   */
  public void track(String incidentId, long timestamp) {
    recentIncidents.put(incidentId, timestamp);
    log.debug("[DeDuplicationCache] Tracked incident: {}", incidentId);
  }

  /**
   * Clean old incidents (older than throttle window).
   *
   * <p>Should be called periodically to prevent memory leaks from accumulating stale incident IDs.
   *
   * @param now Current timestamp in milliseconds
   * @return Number of incidents removed
   */
  public int cleanOld(long now) {
    long threshold = now - throttleWindowMs;

    // removeIf returns boolean, so we track count manually
    AtomicInteger removedCount = new AtomicInteger(0);
    recentIncidents
        .entrySet()
        .removeIf(
            entry -> {
              boolean isOld = entry.getValue() < threshold;
              if (isOld) {
                log.debug("[DeDuplicationCache] Cleaned old incident: {}", entry.getKey());
                removedCount.incrementAndGet();
              }
              return isOld;
            });

    int count = removedCount.get();
    if (count > 0) {
      log.debug("[DeDuplicationCache] Cleaned {} old incidents", count);
    }
    return count;
  }

  /**
   * Get current cache size.
   *
   * <p>Useful for monitoring and diagnostics.
   *
   * @return Number of tracked incidents
   */
  public int size() {
    return recentIncidents.size();
  }

  /**
   * Clear all tracked incidents.
   *
   * <p>Useful for testing or manual reset.
   *
   * @return Number of incidents cleared
   */
  public int clear() {
    int size = recentIncidents.size();
    recentIncidents.clear();
    log.info("[DeDuplicationCache] Cleared {} tracked incidents", size);
    return size;
  }

  /**
   * Check if cache contains a specific incident.
   *
   * @param incidentId The incident ID to check
   * @return true if incident is tracked, false otherwise
   */
  public boolean contains(String incidentId) {
    return recentIncidents.containsKey(incidentId);
  }

  /**
   * Get the throttle window duration in milliseconds.
   *
   * @return Throttle window duration
   */
  public long getThrottleWindowMs() {
    return throttleWindowMs;
  }
}
