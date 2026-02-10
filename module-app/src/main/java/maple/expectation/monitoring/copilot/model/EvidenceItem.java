package maple.expectation.monitoring.copilot.model;

import lombok.Builder;

@Builder
public record EvidenceItem(String type, String title, String body) {}
