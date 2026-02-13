package maple.expectation.monitoring.copilot.ingestor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.error.exception.InternalSystemException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.monitoring.copilot.model.SeverityMapping;
import maple.expectation.monitoring.copilot.model.SignalDefinition;

/**
 * Grafana Dashboard JSON Ingestor Parses dashboard JSON files and extracts PromQL signal
 * definitions.
 *
 * <h3>CLAUDE.md Compliance</h3>
 *
 * <ul>
 *   <li>Section 12: All exceptions handled via LogicExecutor
 *   <li>No try-catch blocks in business logic
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class GrafanaJsonIngestor {

  private final ObjectMapper objectMapper;
  private final LogicExecutor executor;

  private static final String PANEL_TYPE_ROW = "row";
  private static final int HIGH_PRIORITY_SCORE = 100;
  private static final int MEDIUM_PRIORITY_SCORE = 50;
  private static final int LOW_PRIORITY_SCORE = 10;

  /**
   * Parse dashboard JSON files and extract signal definitions
   *
   * @param dir Directory containing dashboard JSON files
   * @return List of extracted signal definitions
   */
  public List<SignalDefinition> ingestDashboards(Path dir) {
    if (!Files.exists(dir)) {
      log.warn("Dashboard directory does not exist: {}", dir);
      return List.of();
    }

    return executor.executeOrDefault(
        () -> ingestDashboardsInternal(dir),
        List.of(),
        TaskContext.of("GrafanaJsonIngestor", "IngestDashboards", dir.toString()));
  }

  /**
   * Internal ingestion with checked exceptions. Wrapped by LogicExecutor for proper exception
   * handling.
   */
  private List<SignalDefinition> ingestDashboardsInternal(Path dir) {
    List<SignalDefinition> signals = new ArrayList<>();

    try (Stream<Path> paths = Files.walk(dir)) {
      paths
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".json"))
          .forEach(
              jsonPath -> {
                List<SignalDefinition> dashboardSignals = parseDashboard(jsonPath);
                signals.addAll(dashboardSignals);
              });
    } catch (Exception e) {
      throw new InternalSystemException("IngestDashboardsInternal", e);
    }

    log.info("Ingested {} signals from {} dashboards", signals.size(), dir);
    return signals;
  }

  /** Parse single dashboard JSON file. */
  private List<SignalDefinition> parseDashboard(Path jsonPath) {
    return executor.executeOrDefault(
        () -> parseDashboardInternal(jsonPath),
        List.of(),
        TaskContext.of("GrafanaJsonIngestor", "ParseDashboard", jsonPath.getFileName().toString()));
  }

  /** Internal parse implementation with checked exceptions. */
  private List<SignalDefinition> parseDashboardInternal(Path jsonPath) throws Exception {
    String jsonContent = Files.readString(jsonPath);
    JsonNode root = objectMapper.readTree(jsonContent);
    String dashboardUid = root.path("uid").asText();
    String dashboardTitle = root.path("title").asText();

    List<SignalDefinition> signals = new ArrayList<>();
    JsonNode panels = root.path("panels");

    extractPanelsRecursively(panels, dashboardUid, dashboardTitle, signals);

    return signals;
  }

  /** Recursively extract panels from dashboard structure. */
  private void extractPanelsRecursively(
      JsonNode panels, String dashboardUid, String dashboardTitle, List<SignalDefinition> signals)
      throws Exception {
    if (panels == null || panels.isMissingNode()) {
      return;
    }

    for (JsonNode panel : panels) {
      String panelType = panel.path("type").asText();

      // Recursively handle nested row panels
      if (PANEL_TYPE_ROW.equals(panelType)) {
        JsonNode nestedPanels = panel.path("panels");
        extractPanelsRecursively(nestedPanels, dashboardUid, dashboardTitle, signals);
        continue;
      }

      // Extract signals from panels with targets
      JsonNode targets = panel.path("targets");
      if (targets == null || targets.isEmpty() || !targets.isArray()) {
        continue;
      }

      String panelTitle = panel.path("title").asText("Untitled Panel");
      int panelId = panel.path("id").asInt(-1);

      for (JsonNode target : targets) {
        String expr = target.path("expr").asText(null);
        if (expr == null || expr.isBlank()) {
          continue;
        }

        String refId = target.path("refId").asText("A");
        String datasourceType = detectDatasourceType(panel, target);
        if (!"prometheus".equalsIgnoreCase(datasourceType)) {
          continue;
        }

        SeverityMapping thresholds = extractThresholds(panel);
        int priority = calculatePriority(panelTitle, expr);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("dashboardTitle", dashboardTitle);
        metadata.put("panelId", String.valueOf(panelId));
        metadata.put("priorityScore", String.valueOf(priority));

        signals.add(
            SignalDefinition.builder()
                .id(generateStableId(dashboardUid, panelId, refId, expr))
                .dashboardUid(dashboardUid)
                .panelTitle(panelTitle)
                .datasourceType(datasourceType)
                .query(expr)
                .legend(target.path("legendFormat").asText(""))
                .unit(panel.path("fieldConfig").path("defaults").path("unit").asText(""))
                .severityMapping(thresholds)
                .sloTag(inferSloTag(panelTitle, expr))
                .metadata(metadata)
                .build());
      }
    }
  }

  /** Detect datasource type from panel/target metadata. */
  private String detectDatasourceType(JsonNode panel, JsonNode target) {
    String ds = target.path("datasource").path("type").asText("");
    if (!ds.isBlank()) {
      return ds;
    }
    ds = panel.path("datasource").path("type").asText("");
    if (!ds.isBlank()) {
      return ds;
    }
    return "prometheus"; // Default assumption
  }

  /** Extract threshold values from Grafana threshold configuration. */
  private SeverityMapping extractThresholds(JsonNode panel) {
    JsonNode steps = panel.path("fieldConfig").path("defaults").path("thresholds").path("steps");

    if (!steps.isArray() || steps.size() < 2) {
      return SeverityMapping.builder().build();
    }

    Double warnThreshold = null;
    Double critThreshold = null;

    int stepCount = 0;
    for (JsonNode step : steps) {
      if (!step.hasNonNull("value")) {
        continue;
      }
      double value = step.get("value").asDouble();
      if (stepCount == 0) {
        warnThreshold = value;
      } else if (stepCount == 1) {
        critThreshold = value;
        break;
      }
      stepCount++;
    }

    return SeverityMapping.builder()
        .warnThreshold(warnThreshold)
        .critThreshold(critThreshold)
        .comparator(">")
        .build();
  }

  /** Calculate signal priority based on keywords in title and query. */
  private int calculatePriority(String title, String expr) {
    String lower = (title + " " + expr).toLowerCase();

    // High priority keywords
    if (lower.contains("p99")
        || lower.contains("error")
        || lower.contains("timeout")
        || lower.contains("deadletter")
        || lower.contains("dlq")
        || lower.contains("lag")
        || lower.contains("pending")
        || lower.contains("pool")
        || lower.contains("gc")
        || lower.contains("oom")
        || lower.contains("lock")) {
      return HIGH_PRIORITY_SCORE;
    }

    // Medium priority keywords
    if (lower.contains("hit rate") || lower.contains("throughput") || lower.contains("latency")) {
      return MEDIUM_PRIORITY_SCORE;
    }

    return LOW_PRIORITY_SCORE;
  }

  /** Infer SLO tag from panel title and query. */
  private String inferSloTag(String title, String expr) {
    String lower = (title + " " + expr).toLowerCase();
    if (lower.contains("p99")) return "latency.p99";
    if (lower.contains("error") || expr.contains("5..")) return "error.rate";
    return "generic";
  }

  /** Generate stable ID from dashboard metadata. */
  private String generateStableId(String dashboardUid, int panelId, String refId, String expr)
      throws Exception {
    String raw = dashboardUid + "|" + panelId + "|" + refId + "|" + expr;
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] hash = md.digest(raw.getBytes());
    return HexFormat.of().formatHex(hash).substring(0, 16);
  }
}
