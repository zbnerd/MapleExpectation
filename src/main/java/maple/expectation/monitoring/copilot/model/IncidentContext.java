package maple.expectation.monitoring.copilot.model;

import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record IncidentContext(

    String incidentId,
    String summary,
    List<AnomalyEvent> anomalies,
    List<?> evidence, // Supports both EvidenceItem and RichEvidence
    Map<String, Object> metadata
) {}