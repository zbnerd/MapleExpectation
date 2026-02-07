package maple.expectation.monitoring.copilot.model;

import lombok.Builder;

@Builder
public record MetricPoint(Long epochMillis, Double value) {}
