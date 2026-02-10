package maple.expectation.monitoring.copilot.model;

import java.util.List;
import java.util.Map;
import lombok.Builder;

@Builder
public record MitigationPlan(
    List<Hypothesis> hypotheses,
    List<Action> actions,
    List<String> questionsToConfirm,
    String riskLevel,
    Map<String, Object> rollbackPlan) {}
