package maple.expectation.monitoring.copilot.model;

import lombok.Builder;

import java.util.List;

@Builder
public record TimeSeries(

    String label,
    List<MetricPoint> points
) {}