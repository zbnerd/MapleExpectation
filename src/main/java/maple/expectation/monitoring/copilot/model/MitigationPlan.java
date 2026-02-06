package maple.expectation.monitoring.copilot.model;

import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record MitigationPlan(

    List<Hypothesis> hypotheses,
    List<Action> actions,
    List<String> questionsToConfirm,
    String riskLevel,
    Map<String, Object> rollbackPlan
) {}