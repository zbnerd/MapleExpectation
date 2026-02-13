package maple.expectation.monitoring.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Component;

/**
 * AI 응답 파서 (SRP: JSON 파싱 전담)
 *
 * <h3>책임</h3>
 *
 * <ul>
 *   <li>OpenAI API 응답 파싱
 *   <li>AiAnalysisResult 생성
 *   <li>MitigationPlan JSON 파싱
 *   <li>Markdown 코드 블록 처리
 *   <li>파싱 실패 시 안전한 기본값 반환
 * </ul>
 *
 * <h3>CLAUDE.md Section 12 준수</h3>
 *
 * <ul>
 *   <li>LogicExecutor.executeWithFallback()로 예외 처리
 *   <li>Raw try-catch 사용 금지
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiResponseParser {

  private final LogicExecutor executor;

  /** AI 응답 파싱 (에러 분석) */
  public AiSreService.AiAnalysisResult parseAiResponse(
      String response, Throwable originalException) {
    return AiSreService.AiAnalysisResult.builder()
        .rootCause(extractSection(response, "Root Cause", "원인 분석 중"))
        .severity(extractSection(response, "Severity", "MEDIUM"))
        .affectedComponents(extractSection(response, "Affected Components", "확인 필요"))
        .actionItems(extractSection(response, "Action Items", "수동 점검 필요"))
        .analysisSource("AI_GPT4O_MINI")
        .disclaimer("이 분석은 AI가 생성한 결과이므로 검증이 필요합니다.")
        .build();
  }

  /** 응답에서 섹션 추출 */
  private String extractSection(String response, String sectionName, String defaultValue) {
    String[] lines = response.split("\n");
    StringBuilder result = new StringBuilder();
    boolean capturing = false;

    for (String line : lines) {
      if (line.contains(sectionName) || line.contains("**" + sectionName + "**")) {
        capturing = true;
        // 같은 줄에 내용이 있으면 추출
        int colonIndex = line.indexOf(":");
        if (colonIndex != -1 && colonIndex < line.length() - 1) {
          result.append(line.substring(colonIndex + 1).trim());
        }
        continue;
      }
      if (capturing) {
        if (line.startsWith("**") || line.startsWith("#") || line.isBlank()) {
          break;
        }
        result.append(line.trim()).append(" ");
      }
    }

    String extracted = result.toString().trim();
    return extracted.isEmpty() ? defaultValue : extracted;
  }

  /**
   * JSON 응답 파싱 (인시던트 완화 계획)
   *
   * <p>CLAUDE.md Section 12: LogicExecutor.executeWithFallback() 패턴 사용
   *
   * @param jsonResponse OpenAI 응답 (JSON 형식)
   * @param incidentId 인시던트 ID
   * @return 파싱된 완화 계획
   */
  public AiSreService.MitigationPlan parseMitigationPlanJson(
      String jsonResponse, String incidentId) {
    // Markdown 코드 블록 제거 (ChatGPT가 ```json ... ```으로 감싸는 경우 대응)
    String cleanedResponse = removeMarkdownCodeBlocks(jsonResponse);

    return executor.executeWithFallback(
        () -> parseMitigationPlanInternal(cleanedResponse, incidentId),
        e -> createFallbackMitigationPlan(incidentId, e),
        TaskContext.of("AiResponseParser", "ParseMitigationPlan", incidentId));
  }

  /**
   * 내부 JSON 파싱 로직 (checked exception 발생 가능)
   *
   * <p>LogicExecutor.executeWithFallback()로 래핑되어 예외 처리
   */
  private AiSreService.MitigationPlan parseMitigationPlanInternal(
      String cleanedResponse, String incidentId) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var planNode = mapper.readTree(cleanedResponse);

    // hypotheses 파싱
    List<AiSreService.Hypothesis> hypotheses =
        mapper.convertValue(
            planNode.get("hypotheses"),
            mapper
                .getTypeFactory()
                .constructCollectionType(List.class, AiSreService.Hypothesis.class));

    // actions 파싱
    List<AiSreService.Action> actions =
        mapper.convertValue(
            planNode.get("actions"),
            mapper.getTypeFactory().constructCollectionType(List.class, AiSreService.Action.class));

    // questions 파싱
    List<AiSreService.ClarifyingQuestion> questions =
        mapper.convertValue(
            planNode.get("questions"),
            mapper
                .getTypeFactory()
                .constructCollectionType(List.class, AiSreService.ClarifyingQuestion.class));

    // rollbackPlan 파싱
    AiSreService.RollbackPlan rollbackPlan =
        mapper.convertValue(planNode.get("rollbackPlan"), AiSreService.RollbackPlan.class);

    return new AiSreService.MitigationPlan(
        incidentId,
        "AI_GPT4O_MINI",
        hypotheses,
        actions,
        questions,
        rollbackPlan,
        "AI가 생성한 완화 계획입니다. 검증 후 실행을 권장합니다.");
  }

  /** Markdown 코드 블록 제거 */
  private String removeMarkdownCodeBlocks(String response) {
    String cleaned = response;
    if (cleaned.contains("```")) {
      int firstBacktick = cleaned.indexOf("```");
      if (firstBacktick != -1) {
        // 첫 번째 라인 끝나는 개행 문자 찾기
        int newlineAfterFirstBlock = cleaned.indexOf("\n", firstBacktick);
        if (newlineAfterFirstBlock != -1) {
          cleaned = cleaned.substring(newlineAfterFirstBlock + 1);
        }

        // 마지막 ``` 제거
        int lastBacktick = cleaned.lastIndexOf("```");
        if (lastBacktick != -1) {
          cleaned = cleaned.substring(0, lastBacktick);
        }
      }
    }
    return cleaned;
  }

  /** 파싱 실패 시 기본 완화 계획 생성 (executeWithFallback의 fallback 람다) */
  private AiSreService.MitigationPlan createFallbackMitigationPlan(String incidentId, Throwable e) {
    log.error("[AiResponseParser] JSON 파싱 실패, 기본 계획 반환: {}", e.getMessage());
    return new AiSreService.MitigationPlan(
        incidentId,
        "AI_PARSE_FAILED",
        List.of(new AiSreService.Hypothesis("JSON 파싱 실패", "LOW", List.of(e.getMessage()))),
        List.of(new AiSreService.Action(1, "수동 분석 실행", "LOW", "LLM 응답 확인 필요")),
        List.of(),
        new AiSreService.RollbackPlan("즉시", List.of("수동 개입")),
        "JSON 파싱 실패로 기본 계획을 반환했습니다.");
  }
}
