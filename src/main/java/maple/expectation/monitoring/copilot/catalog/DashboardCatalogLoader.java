package maple.expectation.monitoring.copilot.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.monitoring.copilot.ingestor.GrafanaJsonIngestor;
import maple.expectation.monitoring.copilot.model.SignalDefinition;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catalog loader for dashboard signal definitions.
 * Provides in-memory caching and reload capability for Grafana dashboard JSON files.
 */
@Slf4j
@RequiredArgsConstructor
public class DashboardCatalogLoader {

    private final GrafanaJsonIngestor ingestor;

    private final ConcurrentHashMap<String, SignalDefinition> signalCache = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    /**
     * Load all dashboard JSON files from directory and cache signal definitions.
     *
     * @param dir Directory containing *.json dashboard files
     * @return List of extracted signal definitions
     */
    public List<SignalDefinition> loadAll(Path dir) {
        log.info("Loading dashboard catalog from: {}", dir);

        List<SignalDefinition> signals = ingestor.ingestDashboards(dir);

        signals.forEach(signal -> {
            String stableId = signal.dashboardUid() + ":" + signal.panelTitle();
            signalCache.put(stableId, signal);
        });

        loaded = true;
        log.info("Cached {} signal definitions from {} dashboards", signals.size(), dir);
        return signals;
    }

    /**
     * Get cached signal by stable ID (dashboardUid:panelTitle).
     *
     * @param stableId Stable identifier in format "dashboardUid:panelTitle"
     * @return SignalDefinition or null if not found
     */
    public SignalDefinition getSignal(String stableId) {
        return signalCache.get(stableId);
    }

    /**
     * Get all cached signals.
     *
     * @return List of all cached signal definitions
     */
    public List<SignalDefinition> getAllSignals() {
        return List.copyOf(signalCache.values());
    }

    /**
     * Clear cache and reload from directory.
     *
     * @param dir Directory containing *.json dashboard files
     * @return List of freshly loaded signal definitions
     */
    public List<SignalDefinition> reload(Path dir) {
        log.info("Reloading dashboard catalog from: {}", dir);
        signalCache.clear();
        loaded = false;
        return loadAll(dir);
    }

    /**
     * Check if catalog has been loaded.
     *
     * @return true if signals are cached
     */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Get current cache size.
     *
     * @return Number of cached signals
     */
    public int size() {
        return signalCache.size();
    }

    /**
     * Clear the signal cache.
     */
    public void clear() {
        log.info("Clearing dashboard catalog cache");
        signalCache.clear();
        loaded = false;
    }
}
