package maple.expectation.monitoring.copilot.model;

import java.util.List;
import java.util.Map;
import lombok.Builder;

@Builder
public record IncidentContext(
    String incidentId,
    String summary,
    List<AnomalyEvent> anomalies,
    List<?> evidence, // Supports both EvidenceItem and RichEvidence
    Map<String, Object> metadata) {}
