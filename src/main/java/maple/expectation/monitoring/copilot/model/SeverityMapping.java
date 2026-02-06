package maple.expectation.monitoring.copilot.model;

import lombok.Builder;

@Builder
public record SeverityMapping(

    Double warnThreshold,
    Double critThreshold,
    String comparator
) {}