package maple.expectation.monitoring.copilot.model;

import lombok.Builder;

@Builder
public record AnomalyEvent(
    String signalId,
    String severity,
    String reason,
    Long detectedAtMillis,
    Double currentValue,
    Double baselineValue) {}
