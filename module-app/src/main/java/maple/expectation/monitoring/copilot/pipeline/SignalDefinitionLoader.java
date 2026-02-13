package maple.expectation.monitoring.copilot.pipeline;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.monitoring.copilot.ingestor.GrafanaJsonIngestor;
import maple.expectation.monitoring.copilot.model.SignalDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Signal Definition Loader (Stateless Design - Issue #285)
 *
 * <h3>Responsibility</h3>
 *
 * <p>Loads and caches signal definitions from Grafana dashboard JSON files. Provides a single
 * source of truth for monitoring signal catalog with 5-minute cache TTL.
 *
 * <h3>Stateless Cache Strategy</h3>
 *
 * <ul>
 *   <li>Cache: Caffeine (L1 in-memory) with automatic expiration
 *   <li>Cache duration: 5 minutes (configurable via {@code monitoring.copilot.cache-ttl-ms})
 *   <li>Thread safety: Caffeine's concurrent data structures (no volatile fields)
 *   <li>Stateless: No instance-level mutable state except the cache itself
 * </ul>
 *
 * <h3>LogicExecutor Compliance</h3>
 *
 * <p>All file I/O operations wrapped in {@link LogicExecutor#executeOrDefault()} for resilience and
 * observability (Section 12).
 *
 * @see GrafanaJsonIngestor
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monitoring.copilot.enabled", havingValue = "true")
public class SignalDefinitionLoader {

  private static final String CACHE_KEY = "signalCatalog";

  private final GrafanaJsonIngestor ingestor;
  private final LogicExecutor executor;

  @Value("${monitoring.copilot.grafana.dashboard-dir:./dashboards}")
  private String dashboardDir;

  @Value("${monitoring.copilot.cache-ttl-ms:300000}")
  private long cacheTtlMs;

  /** Caffeine cache with TTL-based expiration (thread-safe by design) */
  private final Cache<String, List<SignalDefinition>> signalCatalogCache =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(cacheTtlMs)).recordStats().build();

  /**
   * Load signal definitions from Grafana dashboards with caching.
   *
   * <p>Returns cached catalog if available and not expired, otherwise reloads from disk.
   *
   * @return List of signal definitions, empty list if loading fails
   */
  public List<SignalDefinition> loadSignalDefinitions() {
    return signalCatalogCache.get(CACHE_KEY, key -> loadSignalDefinitionsFromDisk());
  }

  /**
   * Force reload signal catalog bypassing cache.
   *
   * <p>Useful for manual refresh or when cache needs immediate invalidation.
   *
   * @return Freshly loaded signal definitions
   */
  public List<SignalDefinition> forceReload() {
    signalCatalogCache.invalidate(CACHE_KEY);
    return loadSignalDefinitions();
  }

  /**
   * Get current cache size without loading.
   *
   * @return Number of signals currently cached
   */
  public int getCacheSize() {
    List<SignalDefinition> cached = signalCatalogCache.getIfPresent(CACHE_KEY);
    return cached != null ? cached.size() : 0;
  }

  /**
   * Check if cache is populated.
   *
   * @return true if cache contains data, false otherwise
   */
  public boolean isCached() {
    return signalCatalogCache.getIfPresent(CACHE_KEY) != null;
  }

  /**
   * Get cache statistics for monitoring.
   *
   * @return Cache statistics
   */
  public CacheStats getCacheStats() {
    return signalCatalogCache.stats();
  }

  /** Load signal catalog from Grafana dashboard JSON files. */
  private List<SignalDefinition> loadSignalDefinitionsFromDisk() {
    return executor.executeOrDefault(
        () -> {
          Path dashboardPath = Path.of(dashboardDir);
          List<SignalDefinition> signals = ingestor.ingestDashboards(dashboardPath);

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
