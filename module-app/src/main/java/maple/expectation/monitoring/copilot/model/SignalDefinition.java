package maple.expectation.monitoring.copilot.model;

import java.util.Map;
import lombok.Builder;

@Builder
public record SignalDefinition(
    String id,
    String dashboardUid,
    String panelTitle,
    String datasourceType,
    String query,
    String legend,
    String unit,
    SeverityMapping severityMapping,
    String sloTag,
    Map<String, String> metadata) {}
