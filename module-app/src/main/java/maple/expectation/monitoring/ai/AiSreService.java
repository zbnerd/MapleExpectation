package maple.expectation.monitoring.ai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.monitoring.context.SystemContextProvider;
import maple.expectation.monitoring.copilot.model.IncidentContext;
import maple.expectation.monitoring.security.PiiMaskingFilter;
import maple.expectation.monitoring.throttle.AlertThrottler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * AI SRE 분석 서비스 (Issue #251)
 *
 * <h3>기능</h3>
 *
 * <ul>
 *   <li>LangChain4j 기반 에러 분석
 *   <li>시스템 컨텍스트 자동 수집
 *   <li>PII 마스킹 후 LLM 전송
 *   <li>Fallback 3단계 체인
 * </ul>
 *
 * <h4>Fallback 체인 (3단계)</h4>
 *
 * <pre>
 * 1. Primary: OpenAI GPT-4o-mini 분석
 * 2. Secondary: 규칙 기반 분석 (키워드 매칭)
 * 3. Tertiary: 기본 에러 메시지 반환
 * </pre>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 *
 * <ul>
 *   <li>Section 12 (LogicExecutor): AI 호출도 executor 패턴
 *   <li>Section 12-1 (Circuit Breaker): openAiApi CB 적용
 *   <li>Section 6 (SOLID): SRP 준수를 위해 3개의 전문 컴포넌트로 분리
 * </ul>
 *
 * @see AiPromptBuilder 프롬프트 생성 전담
 * @see AiResponseParser JSON 파싱 전담
 * @see AiAnalysisFormatter 결과 포맷팅 전담
 * @see SystemContextProvider
 * @see PiiMaskingFilter
 * @see AlertThrottler
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.sre.enabled", havingValue = "true")
public class AiSreService {

  private final ChatLanguageModel chatModel;
  private final SystemContextProvider contextProvider;
  private final PiiMaskingFilter piiFilter;
  private final AlertThrottler throttler;
  private final LogicExecutor executor;
  private final Executor aiTaskExecutor;
  private final AiPromptBuilder promptBuilder;
  private final AiResponseParser responseParser;
  private final AiAnalysisFormatter formatter;

  public AiSreService(
      ChatLanguageModel chatModel,
      SystemContextProvider contextProvider,
      PiiMaskingFilter piiFilter,
      AlertThrottler throttler,
      LogicExecutor executor,
      @Qualifier("aiTaskExecutor") Executor aiTaskExecutor,
      AiPromptBuilder promptBuilder,
      AiResponseParser responseParser,
      AiAnalysisFormatter formatter) {
    this.chatModel = chatModel;
    this.contextProvider = contextProvider;
    this.piiFilter = piiFilter;
    this.throttler = throttler;
    this.executor = executor;
    this.aiTaskExecutor = aiTaskExecutor;
    this.promptBuilder = promptBuilder;
    this.responseParser = responseParser;
    this.formatter = formatter;
  }

  @Value("${ai.sre.enabled:false}")
  private boolean aiEnabled;

  /**
   * 에러 분석 수행 (비동기)
   *
   * @param exception 분석할 예외
   * @return AI 분석 결과 (Optional - 실패 시 empty)
   */
  public CompletableFuture<Optional<AiAnalysisResult>> analyzeErrorAsync(Throwable exception) {
    return CompletableFuture.supplyAsync(() -> analyzeError(exception), aiTaskExecutor);
  }

  /**
   * 에러 분석 수행 (동기)
   *
   * <p>Circuit Breaker가 적용된 분석을 수행합니다. AOP Proxy가 작동하려면 public 메서드여야 합니다.
   *
   * @param exception 분석할 예외
   * @return AI 분석 결과 (Optional - 실패 시 empty)
   */
  @CircuitBreaker(name = "openAiApi", fallbackMethod = "fallbackAnalysis")
  public Optional<AiAnalysisResult> analyzeError(Throwable exception) {
    TaskContext context =
        TaskContext.of("AiSre", "AnalyzeError", exception.getClass().getSimpleName());

    // 스로틀링 체크
    String errorPattern = exception.getClass().getSimpleName();
    if (!throttler.canSendAiAnalysisWithThrottle(errorPattern)) {
      log.debug("[AiSre] 스로틀링으로 AI 분석 스킵: {}", errorPattern);
      return Optional.of(createThrottledResult(exception));
    }

    return executor.executeOrDefault(
        () -> performAnalysisInternal(exception), Optional.empty(), context);
  }

  /** 내부 AI 분석 로직 (Circuit Breaker 내부에서 호출) */
  private Optional<AiAnalysisResult> performAnalysisInternal(Throwable exception) {
    // 1. 시스템 컨텍스트 수집
    String systemContext = contextProvider.buildContextForAi();

    // 2. PII 마스킹 (스택 트레이스)
    String maskedStackTrace = piiFilter.maskStackTrace(getTopStackTrace(exception, 5));

    // 3. 프롬프트 생성 (AiPromptBuilder 위임)
    AiPromptBuilder.PromptWithSystem prompt =
        promptBuilder.buildAnalysisPrompt(exception, maskedStackTrace, systemContext);

    // 4. LLM 호출
    String response = chatModel.generate(prompt.systemPrompt() + "\n\n" + prompt.userPrompt());

    // 5. 결과 파싱 (AiResponseParser 위임)
    return Optional.of(responseParser.parseAiResponse(response, exception));
  }

  /** Fallback: 규칙 기반 분석 (Circuit Breaker Open 또는 LLM 실패 시) */
  @SuppressWarnings("unused")
  Optional<AiAnalysisResult> fallbackAnalysis(Throwable exception, Throwable cause) {
    log.warn("[AiSre] LLM 분석 실패, 규칙 기반 분석으로 전환: {}", cause.getMessage());

    String errorType = exception.getClass().getSimpleName();
    String message = exception.getMessage() != null ? exception.getMessage() : "Unknown error";

    // 규칙 기반 분석
    String rootCause = analyzeByKeyword(errorType, message);
    String severity = determineSeverity(errorType, message);

    return Optional.of(
        AiAnalysisResult.builder()
            .rootCause(rootCause)
            .severity(severity)
            .affectedComponents(inferAffectedComponents(errorType))
            .actionItems(suggestActions(errorType))
            .analysisSource("RULE_BASED")
            .disclaimer("규칙 기반 분석 결과입니다. 수동 검증을 권장합니다.")
            .build());
  }

  /** 스로틀링된 결과 생성 */
  private AiAnalysisResult createThrottledResult(Throwable exception) {
    return AiAnalysisResult.builder()
        .rootCause("동일 에러 패턴 반복 발생")
        .severity("INFO")
        .affectedComponents(exception.getClass().getSimpleName())
        .actionItems("이전 분석 결과 참조")
        .analysisSource("THROTTLED")
        .disclaimer("스로틀링으로 인해 새로운 AI 분석이 생략되었습니다.")
        .build();
  }

  /** 키워드 기반 원인 분석 */
  private String analyzeByKeyword(String errorType, String message) {
    String combined = (errorType + " " + message).toLowerCase();

    if (combined.contains("timeout") || combined.contains("timed out")) {
      return "타임아웃 발생 - 외부 서비스 응답 지연 또는 네트워크 문제";
    }
    if (combined.contains("connection") && combined.contains("refused")) {
      return "연결 거부 - 대상 서비스 다운 또는 방화벽 차단";
    }
    if (combined.contains("circuit") && combined.contains("open")) {
      return "서킷브레이커 오픈 - 연속 장애로 보호 모드 진입";
    }
    if (combined.contains("redis") || combined.contains("redisson")) {
      return "Redis 관련 오류 - 캐시/락 서비스 문제";
    }
    if (combined.contains("hikari") || combined.contains("connection pool")) {
      return "DB 커넥션 풀 문제 - 커넥션 부족 또는 누수 의심";
    }
    if (combined.contains("outofmemory") || combined.contains("heap")) {
      return "메모리 부족 - JVM 힙 메모리 소진";
    }
    if (combined.contains("thread") && combined.contains("exhaust")) {
      return "스레드 풀 고갈 - 동시 요청 초과";
    }

    return "원인 분석 필요 - 수동 점검 권장";
  }

  /** 심각도 결정 */
  private String determineSeverity(String errorType, String message) {
    String combined = (errorType + " " + message).toLowerCase();

    if (combined.contains("outofmemory") || combined.contains("critical")) {
      return "CRITICAL";
    }
    if (combined.contains("circuit") || combined.contains("pool exhausted")) {
      return "HIGH";
    }
    if (combined.contains("timeout") || combined.contains("connection")) {
      return "MEDIUM";
    }
    return "LOW";
  }

  /** 영향받는 컴포넌트 추론 */
  private String inferAffectedComponents(String errorType) {
    if (errorType.contains("Redis")) {
      return "Redis, TieredCache, LockStrategy";
    }
    if (errorType.contains("Hikari") || errorType.contains("DataSource")) {
      return "MySQL, Repository Layer";
    }
    if (errorType.contains("Nexon") || errorType.contains("External")) {
      return "NexonApiClient, ExternalService";
    }
    if (errorType.contains("CircuitBreaker")) {
      return "Resilience4j, Service Layer";
    }
    return "Unknown";
  }

  /** 조치사항 제안 */
  private String suggestActions(String errorType) {
    if (errorType.contains("Timeout")) {
      return "1. 대상 서비스 상태 확인\n2. 네트워크 지연 점검\n3. 타임아웃 값 검토";
    }
    if (errorType.contains("Connection")) {
      return "1. 연결 대상 서비스 확인\n2. 방화벽/보안그룹 점검\n3. DNS 확인";
    }
    if (errorType.contains("Circuit")) {
      return "1. 서킷브레이커 상태 확인\n2. 장애 원인 파악\n3. 수동 리셋 고려";
    }
    return "1. 로그 상세 확인\n2. 메트릭 모니터링\n3. 개발팀 에스컬레이션";
  }

  /** 스택 트레이스 상위 N개 라인 추출 */
  private String getTopStackTrace(Throwable exception, int count) {
    StackTraceElement[] elements = exception.getStackTrace();
    StringBuilder sb = new StringBuilder();

    for (int i = 0; i < Math.min(count, elements.length); i++) {
      sb.append("  at ").append(elements[i]).append("\n");
    }

    return sb.toString();
  }

  /** AI 분석 결과 Record */
  public record AiAnalysisResult(
      String rootCause,
      String severity,
      String affectedComponents,
      String actionItems,
      String analysisSource,
      String disclaimer) {

    static AiAnalysisResultBuilder builder() {
      return new AiAnalysisResultBuilder();
    }

    static class AiAnalysisResultBuilder {
      private String rootCause;
      private String severity;
      private String affectedComponents;
      private String actionItems;
      private String analysisSource;
      private String disclaimer;

      AiAnalysisResultBuilder rootCause(String rootCause) {
        this.rootCause = rootCause;
        return this;
      }

      AiAnalysisResultBuilder severity(String severity) {
        this.severity = severity;
        return this;
      }

      AiAnalysisResultBuilder affectedComponents(String affectedComponents) {
        this.affectedComponents = affectedComponents;
        return this;
      }

      AiAnalysisResultBuilder actionItems(String actionItems) {
        this.actionItems = actionItems;
        return this;
      }

      AiAnalysisResultBuilder analysisSource(String analysisSource) {
        this.analysisSource = analysisSource;
        return this;
      }

      AiAnalysisResultBuilder disclaimer(String disclaimer) {
        this.disclaimer = disclaimer;
        return this;
      }

      AiAnalysisResult build() {
        return new AiAnalysisResult(
            rootCause, severity, affectedComponents, actionItems, analysisSource, disclaimer);
      }
    }
  }

  /**
   * 인시던트 분석 및 완화 계획 생성
   *
   * @param context 인시던트 컨텍스트 (이상 징후, 증거, 시스템 정보)
   * @return 구조화된 완화 계획
   */
  @CircuitBreaker(name = "openAiApi", fallbackMethod = "fallbackIncidentAnalysis")
  public MitigationPlan analyzeIncident(IncidentContext context) {
    TaskContext taskContext = TaskContext.of("AiSre", "AnalyzeIncident", context.incidentId());

    return executor.executeOrDefault(
        () -> performIncidentAnalysisInternal(context),
        createDefaultMitigationPlan(context),
        taskContext);
  }

  /** 내부 인시던트 분석 로직 */
  private MitigationPlan performIncidentAnalysisInternal(IncidentContext context) {
    // 1. 시스템 컨텍스트 수집
    String systemContext = contextProvider.buildContextForAi();

    // 2. 프롬프트 생성 (AiPromptBuilder 위임)
    AiPromptBuilder.PromptWithSystem prompt =
        promptBuilder.buildIncidentAnalysisPrompt(context, systemContext);

    // 3. LLM 호출
    String response = chatModel.generate(prompt.systemPrompt() + "\n\n" + prompt.userPrompt());

    // 4. JSON 파싱 (AiResponseParser 위임)
    return responseParser.parseMitigationPlanJson(response, context.incidentId());
  }

  /** Fallback: 기본 완화 계획 (Circuit Breaker Open 또는 LLM 실패 시) */
  @SuppressWarnings("unused")
  private MitigationPlan fallbackIncidentAnalysis(IncidentContext context, Throwable cause) {
    log.warn(
        "[AiSre] LLM 인시던트 분석 실패, 기본 계획 사용: incidentId={}, cause={}",
        context.incidentId(),
        cause.getMessage());

    return createDefaultMitigationPlan(context);
  }

  /** 기본 완화 계획 생성 (Fallback용) */
  private MitigationPlan createDefaultMitigationPlan(IncidentContext context) {
    List<Hypothesis> defaultHypotheses =
        List.of(
            new Hypothesis("자동 분석 불가 - 수동 점검 필요", "LOW", List.of("LLM 분석 실패", "시스템 로그 수동 확인 필요")));

    List<Action> defaultActions =
        List.of(
            new Action(1, "시스템 로그 확인", "LOW", "현재 상태 파악"),
            new Action(2, "메트릭 모니터링", "LOW", "주요 지표 추적"),
            new Action(3, "개발팀 에스컬레이션", "LOW", "수동 분석 의뢰"));

    List<ClarifyingQuestion> defaultQuestions =
        List.of(new ClarifyingQuestion("인시던트 발생 시점에 배포가 있었나요?", "배포 관련 문제 확인"));

    RollbackPlan rollbackPlan = new RollbackPlan("상태 악화 시 즉시 실행", List.of("이전 커밋으로 롤백", "영향도 재평가"));

    return new MitigationPlan(
        context.incidentId(),
        "RULE_BASED_FALLBACK",
        defaultHypotheses,
        defaultActions,
        defaultQuestions,
        rollbackPlan,
        "AI 분석 실패로 인한 기본 계획입니다. 수동 검증이 필수입니다.");
  }

  /** 인시던트 완화 계획 Record */
  public record MitigationPlan(
      String incidentId,
      String analysisSource,
      List<Hypothesis> hypotheses,
      List<Action> actions,
      List<ClarifyingQuestion> questions,
      RollbackPlan rollbackPlan,
      String disclaimer) {}

  /** 가설 (원인 가설) */
  public record Hypothesis(String cause, String confidence, List<String> evidence) {}

  /** 조치 항목 */
  public record Action(int step, String action, String risk, String expectedOutcome) {}

  /** 명확화 질문 */
  public record ClarifyingQuestion(String question, String why) {}

  /** 롤백 계획 */
  public record RollbackPlan(String trigger, List<String> steps) {}
}
