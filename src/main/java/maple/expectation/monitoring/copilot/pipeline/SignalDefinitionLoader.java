package maple.expectation.monitoring.copilot.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.monitoring.copilot.ingestor.GrafanaJsonIngestor;
import maple.expectation.monitoring.copilot.model.SignalDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Signal Definition Loader (Issue #251)
 *
 * <h3>Responsibility</h3>
 *
 * <p>Loads and caches signal definitions from Grafana dashboard JSON files. Provides a single source
 * of truth for monitoring signal catalog with 5-minute cache TTL.
 *
 * <h3>Cache Strategy</h3>
 *
 * <ul>
 *   <li>Cache duration: 5 minutes (configurable via {@code monitoring.copilot.cache-ttl-ms})
 *   <li>Cache invalidation: Time-based expiration
 *   <li>Thread safety: volatile fields with double-checked locking pattern
 * </ul>
 *
 * <h3>LogicExecutor Compliance</h3>
 *
 * <p>All file I/O operations wrapped in {@link LogicExecutor#executeOrDefault()} for resilience
 * and observability (Section 12).
 *
 * @see GrafanaJsonIngestor
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monitoring.copilot.enabled", havingValue = "true")
public class SignalDefinitionLoader {

  private final GrafanaJsonIngestor ingestor;
  private final LogicExecutor executor;

  @Value("${monitoring.copilot.grafana.dashboard-dir:./dashboards}")
  private String dashboardDir;

  @Value("${monitoring.copilot.cache-ttl-ms:300000}")
  private long cacheTtlMs;

  // Cached signal catalog with volatile visibility
  private volatile List<SignalDefinition> signalCatalogCache = List.of();
  private volatile long catalogLastUpdated = 0L;

  /**
   * Load signal definitions from Grafana dashboards with caching.
   *
   * <p>Returns cached catalog if within TTL, otherwise reloads from disk.
   *
   * @param currentTimestamp Current timestamp in milliseconds for cache validation
   * @return List of signal definitions, empty list if loading fails
   */
  public List<SignalDefinition> loadSignalDefinitions(long currentTimestamp) {
    // Cache hit: return cached data if still valid
    if (isCacheValid(currentTimestamp)) {
      log.debug("[SignalDefinitionLoader] Cache hit - returning {} signals", signalCatalogCache.size());
      return signalCatalogCache;
    }

    // Cache miss: reload from disk
    return reloadSignalCatalog(currentTimestamp);
  }

  /**
   * Force reload signal catalog bypassing cache.
   *
   * <p>Useful for manual refresh or when cache needs immediate invalidation.
   *
   * @return Freshly loaded signal definitions
   */
  public List<SignalDefinition> forceReload() {
    long now = System.currentTimeMillis();
    return reloadSignalCatalog(now);
  }

  /**
   * Get current cache size without loading.
   *
   * @return Number of signals currently cached
   */
  public int getCacheSize() {
    return signalCatalogCache.size();
  }

  /**
   * Check if cache is populated and valid.
   *
   * @param currentTimestamp Current timestamp in milliseconds
   * @return true if cache is valid, false otherwise
   */
  public boolean isCacheValid(long currentTimestamp) {
    return currentTimestamp - catalogLastUpdated < cacheTtlMs && !signalCatalogCache.isEmpty();
  }

  /**
   * Get cache age in milliseconds.
   *
   * @return Age of cache in milliseconds, or -1 if cache is empty
   */
  public long getCacheAge(long currentTimestamp) {
    if (signalCatalogCache.isEmpty()) {
      return -1L;
    }
    return currentTimestamp - catalogLastUpdated;
  }

  /** Reload signal catalog from Grafana dashboard JSON files. */
  private List<SignalDefinition> reloadSignalCatalog(long currentTimestamp) {
    return executor.executeOrDefault(
        () -> {
          Path dashboardPath = Path.of(dashboardDir);
          List<SignalDefinition> signals = ingestor.ingestDashboards(dashboardPath);

          // Update cache atomically
          signalCatalogCache = signals;
          catalogLastUpdated = currentTimestamp;

          log.info(
              "[SignalDefinitionLoader] Signal catalog refreshed: {} signals from {}",
              signals.size(),
              dashboardPath);

          return signals;
        },
        List.of(),
        TaskContext.of("SignalDefinitionLoader", "ReloadCatalog"));
  }
}
