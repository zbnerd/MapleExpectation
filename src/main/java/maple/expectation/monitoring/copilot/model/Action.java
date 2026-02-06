package maple.expectation.monitoring.copilot.model;

import lombok.Builder;

import java.util.Map;

@Builder
public record Action(

    String action,
    Map<String, Object> params,
    String risk,
    String expectedImpact
) {}