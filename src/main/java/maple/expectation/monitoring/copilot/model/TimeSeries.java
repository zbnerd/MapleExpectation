package maple.expectation.monitoring.copilot.model;

import java.util.List;
import lombok.Builder;

@Builder
public record TimeSeries(String label, List<MetricPoint> points) {}
