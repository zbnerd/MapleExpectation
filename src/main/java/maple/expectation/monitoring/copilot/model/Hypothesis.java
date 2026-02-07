package maple.expectation.monitoring.copilot.model;

import lombok.Builder;

@Builder
public record Hypothesis(String cause, Double confidence, String evidence) {}
