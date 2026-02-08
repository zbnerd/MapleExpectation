package maple.expectation.monitoring.ai;

import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import java.util.List;
import java.util.Map;
import maple.expectation.monitoring.copilot.model.AnomalyEvent;
import maple.expectation.monitoring.copilot.model.EvidenceItem;
import maple.expectation.monitoring.copilot.model.IncidentContext;
import maple.expectation.monitoring.security.PiiMaskingFilter;
import org.springframework.stereotype.Component;

/**
 * AI 프롬프트 빌더 (SRP: 프롬프트 생성 전담)
 *
 * <h3>책임</h3>
 *
 * <ul>
 *   <li>SRE 분석 프롬프트 생성
 *   <li>인시던트 분석 프롬프트 생성
 *   <li>시스템 컨텍스트 및 메트릭 포맷팅
 *   <li>PII 마스킹 적용
 * </ul>
 *
 * @see PiiMaskingFilter
 */
@Component
public class AiPromptBuilder {

  private final PiiMaskingFilter piiFilter;

  private static final String SYSTEM_PROMPT =
      """
            You are an SRE expert for MapleExpectation system.

            Architecture Context:
            - TieredCache: Caffeine (L1) → Redis (L2) → MySQL (L3)
            - CircuitBreaker: Resilience4j (nexonApi, likeSyncDb, redisLock, openAiApi)
            - Virtual Threads: Java 21 enabled
            - Distributed Lock: Redis (primary) → MySQL Named Lock (fallback)
            - Buffer Pattern: In-memory → Redis → DB (eventual consistency)

            Analyze the error and provide:
            1) **Root Cause**: 근본 원인 (한 문장)
            2) **Severity**: CRITICAL / HIGH / MEDIUM / LOW
            3) **Affected Components**: 영향받는 컴포넌트 목록
            4) **Action Items**: 즉시 조치사항 (번호 목록)

            Be concise. Response in Korean.
            """;

  private static final PromptTemplate ANALYSIS_TEMPLATE =
      PromptTemplate.from(
          """
            Error Information:
            - Type: {{errorType}}
            - Message: {{errorMessage}}
            - Stack Trace (top 5):
            {{stackTrace}}

            System Context:
            {{systemContext}}

            Analyze this error and provide actionable insights.
            """);

  private static final String INCIDENT_ANALYSIS_SYSTEM_PROMPT =
      """
            You are an SRE incident commander for MapleExpectation system.

            Architecture Context:
            - TieredCache: Caffeine (L1) → Redis (L2) → MySQL (L3)
            - CircuitBreaker: Resilience4j (nexonApi, likeSyncDb, redisLock, openAiApi)
            - Virtual Threads: Java 21 enabled
            - Distributed Lock: Redis (primary) → MySQL Named Lock (fallback)
            - Buffer Pattern: In-memory → Redis → DB (eventual consistency)
            - Chaos Engineering: Nightmare tests N01-N18

            Task: Analyze the incident and provide a mitigation plan.

            Response Format (JSON):
            {
              "hypotheses": [
                {
                  "cause": "Root cause description",
                  "confidence": "HIGH/MEDIUM/LOW",
                  "evidence": ["Supporting evidence 1", "evidence 2"]
                }
              ],
              "actions": [
                {
                  "step": 1,
                  "action": "Action description",
                  "risk": "HIGH/MEDIUM/LOW",
                  "expectedOutcome": "Expected result"
                }
              ],
              "questions": [
                {
                  "question": "Clarifying question",
                  "why": "Why this matters"
                }
              ],
              "rollbackPlan": {
                "trigger": "When to rollback",
                "steps": ["Rollback step 1", "step 2"]
              }
            }

            Be specific and actionable. Response in Korean.
            """;

  private static final PromptTemplate INCIDENT_ANALYSIS_TEMPLATE =
      PromptTemplate.from(
          """
            Incident Summary: {{summary}}
            Incident ID: {{incidentId}}

            Anomaly Events ({{anomalyCount}} detected):
            {{anomalies}}

            Evidence Items ({{evidenceCount}} items):
            {{evidence}}

            System Context:
            {{systemContext}}

            Additional Metadata:
            {{metadata}}

            Analyze this incident and provide a structured mitigation plan in JSON format.
            """);

  public AiPromptBuilder(PiiMaskingFilter piiFilter) {
    this.piiFilter = piiFilter;
  }

  /**
   * 에러 분석 프롬프트 생성
   *
   * @param exception 분석할 예외
   * @param maskedStackTrace PII 마스킹된 스택 트레이스
   * @param systemContext 시스템 컨텍스트
   * @return 시스템 프롬프트 + 사용자 프롬프트 배열
   */
  public PromptWithSystem buildAnalysisPrompt(
      Throwable exception, String maskedStackTrace, String systemContext) {
    String maskedMessage = piiFilter.maskExceptionMessage(exception);
    String maskedContext = piiFilter.mask(systemContext);

    Prompt prompt =
        ANALYSIS_TEMPLATE.apply(
            Map.of(
                "errorType",
                exception.getClass().getSimpleName(),
                "errorMessage",
                maskedMessage,
                "stackTrace",
                maskedStackTrace,
                "systemContext",
                maskedContext));

    return new PromptWithSystem(SYSTEM_PROMPT, prompt.text());
  }

  /**
   * 인시던트 분석 프롬프트 생성
   *
   * @param context 인시던트 컨텍스트
   * @param systemContext 시스템 컨텍스트
   * @return 시스템 프롬프트 + 사용자 프롬프트 배열
   */
  public PromptWithSystem buildIncidentAnalysisPrompt(
      IncidentContext context, String systemContext) {
    // 이상 징후 포맷팅
    String anomaliesText = formatAnomalies(context.anomalies());

    // 증거 항목 포맷팅
    String evidenceText = formatEvidence(context.evidence());

    // 메타데이터 포맷팅
    String metadataText = formatMetadata(context.metadata());

    // PII 마스킹
    String maskedAnomalies = piiFilter.mask(anomaliesText);
    String maskedEvidence = piiFilter.mask(evidenceText);
    String maskedContext = piiFilter.mask(systemContext);
    String maskedMetadata = piiFilter.mask(metadataText);

    Prompt prompt =
        INCIDENT_ANALYSIS_TEMPLATE.apply(
            Map.of(
                "summary",
                context.summary(),
                "incidentId",
                context.incidentId(),
                "anomalyCount",
                context.anomalies().size(),
                "anomalies",
                maskedAnomalies,
                "evidenceCount",
                context.evidence().size(),
                "evidence",
                maskedEvidence,
                "systemContext",
                maskedContext,
                "metadata",
                maskedMetadata));

    return new PromptWithSystem(INCIDENT_ANALYSIS_SYSTEM_PROMPT, prompt.text());
  }

  /** 이상 징후 포맷팅 */
  private String formatAnomalies(List<AnomalyEvent> anomalies) {
    if (anomalies == null || anomalies.isEmpty()) {
      return "이상 징후 없음";
    }

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < anomalies.size(); i++) {
      AnomalyEvent anomaly = anomalies.get(i);
      sb.append(
          String.format(
              """
                    [%d] %s
                        - 심각도: %s
                        - 사유: %s
                        - 감지 시각: %s
                        - 현재값: %.2f (기준: %.2f)
                    """,
              i + 1,
              anomaly.signalId(),
              anomaly.severity(),
              anomaly.reason(),
              anomaly.detectedAtMillis(),
              anomaly.currentValue(),
              anomaly.baselineValue()));
    }
    return sb.toString();
  }

  /** 증거 항목 포맷팅 */
  private String formatEvidence(List<?> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return "증거 없음";
    }

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < evidence.size(); i++) {
      Object item = evidence.get(i);
      if (item instanceof EvidenceItem evidenceItem) {
        sb.append(
            String.format(
                """
                        [%d] %s (%s)
                            %s
                        """,
                i + 1, evidenceItem.title(), evidenceItem.type(), evidenceItem.body()));
      } else if (item
          instanceof maple.expectation.monitoring.copilot.model.RichEvidence richEvidence) {
        sb.append(
            String.format(
                """
                        [%d] %s (PromQL Evidence)
                            Current: %.4f, Baseline: %.4f, Deviation: %s
                            Query: %s
                        """,
                i + 1,
                richEvidence.signalName(),
                richEvidence.currentValue(),
                richEvidence.baselineValue(),
                richEvidence.formattedDeviation(),
                richEvidence.promql()));
      } else {
        sb.append(String.format("[%d] %s\n", i + 1, item.toString()));
      }
    }
    return sb.toString();
  }

  /** 메타데이터 포맷팅 */
  private String formatMetadata(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return "추가 정보 없음";
    }

    StringBuilder sb = new StringBuilder();
    metadata.forEach((key, value) -> sb.append(String.format("- %s: %s\n", key, value)));
    return sb.toString();
  }

  /** 시스템 프롬프트 + 사용자 프롬프트를 포함한 레코드 */
  public record PromptWithSystem(String systemPrompt, String userPrompt) {}
}
