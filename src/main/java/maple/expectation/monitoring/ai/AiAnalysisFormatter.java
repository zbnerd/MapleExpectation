package maple.expectation.monitoring.ai;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * AI 분석 결과 포맷터 (SRP: 결과 포맷팅 전담)
 *
 * <h3>책임</h3>
 *
 * <ul>
 *   <li>AiAnalysisResult를 사람이 읽기 쉬운 형식으로 변환
 *   <li>Discord 메시지 포맷팅
 *   <li>마크다운 형식 변환
 * </ul>
 *
 * <p>참고: AiPromptBuilder에서 이미 formatAnomalies(), formatEvidence(), formatMetadata()를
 * 처리하므로 이 클래스는 최종 결과 포맷팅에 집중합니다.
 */
@Component
public class AiAnalysisFormatter {

  /**
   * AI 분석 결과를 마크다운 형식으로 변환
   *
   * @param result AI 분석 결과
   * @return 마크다운 형식의 문자열
   */
  public String formatAsMarkdown(AiSreService.AiAnalysisResult result) {
    return String.format(
        """
            ## AI SRE 분석 결과

            **근본 원인**: %s

            **심각도**: %s

            **영향받는 컴포넌트**: %s

            **조치사항**:
            %s

            ---
            *분석 출처: %s*
            *%s*
            """,
        result.rootCause(),
        result.severity(),
        result.affectedComponents(),
        indentActionItems(result.actionItems()),
        result.analysisSource(),
        result.disclaimer());
  }

  /**
   * 인시던트 완화 계획을 마크다운 형식으로 변환
   *
   * @param plan 완화 계획
   * @return 마크다운 형식의 문자열
   */
  public String formatAsMarkdown(AiSreService.MitigationPlan plan) {
    StringBuilder sb = new StringBuilder();

    sb.append(String.format("# 인시던트 완화 계획 (ID: %s)\n\n", plan.incidentId()));
    sb.append(String.format("**분석 출처**: %s\n\n", plan.analysisSource()));

    // 가설 (원인 분석)
    sb.append("## 원인 가설 (Hypotheses)\n\n");
    for (AiSreService.Hypothesis hypothesis : plan.hypotheses()) {
      sb.append(
          String.format(
              """
                  ### %s (%s)
                  %s
                  """,
              hypothesis.cause(), hypothesis.confidence(), formatEvidenceList(hypothesis.evidence())));
    }

    // 조치 항목
    sb.append("\n## 조치 계획 (Actions)\n\n");
    for (AiSreService.Action action : plan.actions()) {
      sb.append(
          String.format(
              """
                  ### Step %d: %s (위험도: %s)
                  - 기대 결과: %s
                  """,
              action.step(), action.action(), action.risk(), action.expectedOutcome()));
    }

    // 명확화 질문
    if (!plan.questions().isEmpty()) {
      sb.append("\n## 명확화 질문 (Clarifying Questions)\n\n");
      for (AiSreService.ClarifyingQuestion question : plan.questions()) {
        sb.append(
            String.format(
                """
                    - **Q**: %s
                      - **왜 중요한가**: %s
                    """,
                question.question(), question.why()));
      }
    }

    // 롤백 계획
    sb.append("\n## 롤백 계획 (Rollback Plan)\n\n");
    sb.append(String.format("**실행 조건**: %s\n\n", plan.rollbackPlan().trigger()));
    sb.append("**단계**:\n");
    for (String step : plan.rollbackPlan().steps()) {
      sb.append(String.format("%d. %s\n", plan.rollbackPlan().steps().indexOf(step) + 1, step));
    }

    // Disclaimer
    sb.append(String.format("\n---\n*%s*\n", plan.disclaimer()));

    return sb.toString();
  }

  /**
   * Discord Embed 형식으로 변환 (간소화된 버전)
   *
   * @param result AI 분석 결과
   * @return Discord webhook에 적합한 형식
   */
  public String formatForDiscord(AiSreService.AiAnalysisResult result) {
    return String.format(
        """
            **🤖 AI SRE 분석**

            **🔍 근본 원인**: %s
            **⚠️ 심각도**: %s
            **🎯 영향 컴포넌트**: %s

            **📋 조치사항**:
            %s

            ---
            *출처: %s | %s*
            """,
        result.rootCause(),
        result.severity(),
        result.affectedComponents(),
        indentActionItems(result.actionItems()),
        result.analysisSource(),
        result.disclaimer());
  }

  /**
   * 인시던트 완화 계획을 Discord Embed 형식으로 변환
   *
   * @param plan 완화 계획
   * @return Discord webhook에 적합한 형식
   */
  public String formatForDiscord(AiSreService.MitigationPlan plan) {
    StringBuilder sb = new StringBuilder();

    sb.append(String.format("**🚨 인시던트 완화 계획 (ID: %s)**\n\n", plan.incidentId()));

    // 원인 가설 (상위 3개만)
    sb.append("**🔍 원인 가설**:\n");
    plan.hypotheses().stream()
        .limit(3)
        .forEach(
            h ->
                sb.append(
                    String.format(
                        "- %s (%s)\n", h.cause().length() > 50 ? h.cause().substring(0, 50) + "..." : h.cause(),
                        h.confidence())));

    // 조치 계획 (상위 3개만)
    sb.append("\n**📋 조치 계획**:\n");
    plan.actions().stream()
        .limit(3)
        .forEach(
            a ->
                sb.append(
                    String.format(
                        "%d. %s (위험도: %s)\n", a.step(), a.action().length() > 50 ? a.action().substring(0, 50) + "..." : a.action(),
                        a.risk())));

    sb.append(String.format("\n*출처: %s*\n", plan.analysisSource()));

    return sb.toString();
  }

  /** 조치사항 들여쓰기 */
  private String indentActionItems(String actionItems) {
    if (actionItems == null || actionItems.isBlank()) {
      return "- 조치사항 없음";
    }

    // 이미 번호 매겨진 경우 그대로 사용
    if (actionItems.contains("1.") || actionItems.contains("- ")) {
      return actionItems;
    }

    // 줄바꿈으로 구분된 경우 각 줄에 "- " 추가
    return String.join("\n", actionItems.split("\n")).replaceAll("^(?!-)", "- ");
  }

  /** 증거 목록 포맷팅 */
  private String formatEvidenceList(java.util.List<String> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return "증거 없음";
    }

    StringBuilder sb = new StringBuilder();
    for (String item : evidence) {
      sb.append(String.format("- %s\n", item));
    }
    return sb.toString();
  }
}
