package maple.expectation.monitoring.copilot.model;

import java.util.Map;
import lombok.Builder;

@Builder
public record Action(
    String action, Map<String, Object> params, String risk, String expectedImpact) {}
