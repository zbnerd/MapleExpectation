package maple.expectation.monitoring.ai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.monitoring.context.SystemContextProvider;
import maple.expectation.monitoring.security.PiiMaskingFilter;
import maple.expectation.monitoring.throttle.AlertThrottler;
import maple.expectation.monitoring.copilot.model.IncidentContext;
import maple.expectation.monitoring.copilot.model.AnomalyEvent;
import maple.expectation.monitoring.copilot.model.EvidenceItem;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * AI SRE 분석 서비스 (Issue #251)
 *
 * <h3>기능</h3>
 * <ul>
 *   <li>LangChain4j 기반 에러 분석</li>
 *   <li>시스템 컨텍스트 자동 수집</li>
 *   <li>PII 마스킹 후 LLM 전송</li>
 *   <li>Fallback 3단계 체인</li>
 * </ul>
 *
 * <h4>Fallback 체인 (3단계)</h4>
 * <pre>
 * 1. Primary: OpenAI GPT-4o-mini 분석
 * 2. Secondary: 규칙 기반 분석 (키워드 매칭)
 * 3. Tertiary: 기본 에러 메시지 반환
 * </pre>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 * <ul>
 *   <li>Section 12 (LogicExecutor): AI 호출도 executor 패턴</li>
 *   <li>Section 12-1 (Circuit Breaker): openAiApi CB 적용</li>
 * </ul>
 *
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

    public AiSreService(
            ChatLanguageModel chatModel,
            SystemContextProvider contextProvider,
            PiiMaskingFilter piiFilter,
            AlertThrottler throttler,
            LogicExecutor executor,
            @Qualifier("aiTaskExecutor") Executor aiTaskExecutor) {
        this.chatModel = chatModel;
        this.contextProvider = contextProvider;
        this.piiFilter = piiFilter;
        this.throttler = throttler;
        this.executor = executor;
        this.aiTaskExecutor = aiTaskExecutor;
    }

    @Value("${ai.sre.enabled:false}")
    private boolean aiEnabled;

    private static final String SYSTEM_PROMPT = """
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

    private static final PromptTemplate ANALYSIS_TEMPLATE = PromptTemplate.from("""
            Error Information:
            - Type: {{errorType}}
            - Message: {{errorMessage}}
            - Stack Trace (top 5):
            {{stackTrace}}

            System Context:
            {{systemContext}}

            Analyze this error and provide actionable insights.
            """);

    private static final String INCIDENT_ANALYSIS_SYSTEM_PROMPT = """
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

    private static final PromptTemplate INCIDENT_ANALYSIS_TEMPLATE = PromptTemplate.from("""
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

    /**
     * 에러 분석 수행 (비동기)
     *
     * @param exception 분석할 예외
     * @return AI 분석 결과 (Optional - 실패 시 empty)
     */
    public CompletableFuture<Optional<AiAnalysisResult>> analyzeErrorAsync(Throwable exception) {
        return CompletableFuture.supplyAsync(
                () -> analyzeError(exception),
                aiTaskExecutor
        );
    }

    /**
     * 에러 분석 수행 (동기)
     *
     * <p>Circuit Breaker가 적용된 분석을 수행합니다.
     * AOP Proxy가 작동하려면 public 메서드여야 합니다.</p>
     *
     * @param exception 분석할 예외
     * @return AI 분석 결과 (Optional - 실패 시 empty)
     */
    @CircuitBreaker(name = "openAiApi", fallbackMethod = "fallbackAnalysis")
    public Optional<AiAnalysisResult> analyzeError(Throwable exception) {
        TaskContext context = TaskContext.of("AiSre", "AnalyzeError", exception.getClass().getSimpleName());

        // 스로틀링 체크
        String errorPattern = exception.getClass().getSimpleName();
        if (!throttler.canSendAiAnalysisWithThrottle(errorPattern)) {
            log.debug("[AiSre] 스로틀링으로 AI 분석 스킵: {}", errorPattern);
            return Optional.of(createThrottledResult(exception));
        }

        return executor.executeOrDefault(
                () -> performAnalysisInternal(exception),
                Optional.empty(),
                context
        );
    }

    /**
     * 내부 AI 분석 로직 (Circuit Breaker 내부에서 호출)
     */
    private Optional<AiAnalysisResult> performAnalysisInternal(Throwable exception) {
        // 1. 시스템 컨텍스트 수집
        String systemContext = contextProvider.buildContextForAi();

        // 2. PII 마스킹
        String maskedMessage = piiFilter.maskExceptionMessage(exception);
        String maskedStackTrace = piiFilter.maskStackTrace(getTopStackTrace(exception, 5));
        String maskedContext = piiFilter.mask(systemContext);

        // 3. 프롬프트 생성
        Prompt prompt = ANALYSIS_TEMPLATE.apply(Map.of(
                "errorType", exception.getClass().getSimpleName(),
                "errorMessage", maskedMessage,
                "stackTrace", maskedStackTrace,
                "systemContext", maskedContext
        ));

        // 4. LLM 호출 (ChatLanguageModel.generate() 사용)
        String response = chatModel.generate(SYSTEM_PROMPT + "\n\n" + prompt.text());

        // 5. 결과 파싱
        return Optional.of(parseAiResponse(response, exception));
    }

    /**
     * Fallback: 규칙 기반 분석 (Circuit Breaker Open 또는 LLM 실패 시)
     */
    @SuppressWarnings("unused")
    Optional<AiAnalysisResult> fallbackAnalysis(Throwable exception, Throwable cause) {
        log.warn("[AiSre] LLM 분석 실패, 규칙 기반 분석으로 전환: {}", cause.getMessage());

        String errorType = exception.getClass().getSimpleName();
        String message = exception.getMessage() != null ? exception.getMessage() : "Unknown error";

        // 규칙 기반 분석
        String rootCause = analyzeByKeyword(errorType, message);
        String severity = determineSeverity(errorType, message);

        return Optional.of(AiAnalysisResult.builder()
                .rootCause(rootCause)
                .severity(severity)
                .affectedComponents(inferAffectedComponents(errorType))
                .actionItems(suggestActions(errorType))
                .analysisSource("RULE_BASED")
                .disclaimer("규칙 기반 분석 결과입니다. 수동 검증을 권장합니다.")
                .build());
    }

    /**
     * 스로틀링된 결과 생성
     */
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

    /**
     * 키워드 기반 원인 분석
     */
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

    /**
     * 심각도 결정
     */
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

    /**
     * 영향받는 컴포넌트 추론
     */
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

    /**
     * 조치사항 제안
     */
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

    /**
     * AI 응답 파싱
     */
    private AiAnalysisResult parseAiResponse(String response, Throwable originalException) {
        // 간단한 파싱 (실제 구현에서는 더 정교한 파싱 필요)
        return AiAnalysisResult.builder()
                .rootCause(extractSection(response, "Root Cause", "원인 분석 중"))
                .severity(extractSection(response, "Severity", "MEDIUM"))
                .affectedComponents(extractSection(response, "Affected Components", "확인 필요"))
                .actionItems(extractSection(response, "Action Items", "수동 점검 필요"))
                .analysisSource("AI_GPT4O_MINI")
                .disclaimer("이 분석은 AI가 생성한 결과이므로 검증이 필요합니다.")
                .build();
    }

    /**
     * 응답에서 섹션 추출
     */
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
     * 스택 트레이스 상위 N개 라인 추출
     */
    private String getTopStackTrace(Throwable exception, int count) {
        StackTraceElement[] elements = exception.getStackTrace();
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < Math.min(count, elements.length); i++) {
            sb.append("  at ").append(elements[i]).append("\n");
        }

        return sb.toString();
    }

    /**
     * AI 분석 결과 Record
     */
    @lombok.Builder
    public record AiAnalysisResult(
            String rootCause,
            String severity,
            String affectedComponents,
            String actionItems,
            String analysisSource,
            String disclaimer
    ) {
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
                taskContext
        );
    }

    /**
     * 내부 인시던트 분석 로직
     */
    private MitigationPlan performIncidentAnalysisInternal(IncidentContext context) {
        // 1. 시스템 컨텍스트 수집
        String systemContext = contextProvider.buildContextForAi();

        // 2. 이상 징후 포맷팅
        String anomaliesText = formatAnomalies(context.anomalies());

        // 3. 증거 항목 포맷팅
        String evidenceText = formatEvidence(context.evidence());

        // 4. 메타데이터 포맷팅
        String metadataText = formatMetadata(context.metadata());

        // 5. PII 마스킹
        String maskedAnomalies = piiFilter.mask(anomaliesText);
        String maskedEvidence = piiFilter.mask(evidenceText);
        String maskedContext = piiFilter.mask(systemContext);
        String maskedMetadata = piiFilter.mask(metadataText);

        // 6. 프롬프트 생성
        Prompt prompt = INCIDENT_ANALYSIS_TEMPLATE.apply(Map.of(
                "summary", context.summary(),
                "incidentId", context.incidentId(),
                "anomalyCount", context.anomalies().size(),
                "anomalies", maskedAnomalies,
                "evidenceCount", context.evidence().size(),
                "evidence", maskedEvidence,
                "systemContext", maskedContext,
                "metadata", maskedMetadata
        ));

        // 7. LLM 호출
        String response = chatModel.generate(INCIDENT_ANALYSIS_SYSTEM_PROMPT + "\n\n" + prompt.text());

        // 8. JSON 파싱
        return parseMitigationPlanJson(response, context.incidentId());
    }

    /**
     * Fallback: 기본 완화 계획 (Circuit Breaker Open 또는 LLM 실패 시)
     */
    @SuppressWarnings("unused")
    private MitigationPlan fallbackIncidentAnalysis(IncidentContext context, Throwable cause) {
        log.warn("[AiSre] LLM 인시던트 분석 실패, 기본 계획 사용: incidentId={}, cause={}",
                context.incidentId(), cause.getMessage());

        return createDefaultMitigationPlan(context);
    }

    /**
     * 기본 완화 계획 생성 (Fallback용)
     */
    private MitigationPlan createDefaultMitigationPlan(IncidentContext context) {
        List<Hypothesis> defaultHypotheses = List.of(
                new Hypothesis(
                        "자동 분석 불가 - 수동 점검 필요",
                        "LOW",
                        List.of("LLM 분석 실패", "시스템 로그 수동 확인 필요")
                )
        );

        List<Action> defaultActions = List.of(
                new Action(1, "시스템 로그 확인", "LOW", "현재 상태 파악"),
                new Action(2, "메트릭 모니터링", "LOW", "주요 지표 추적"),
                new Action(3, "개발팀 에스컬레이션", "LOW", "수동 분석 의뢰")
        );

        List<ClarifyingQuestion> defaultQuestions = List.of(
                new ClarifyingQuestion(
                        "인시던트 발생 시점에 배포가 있었나요?",
                        "배포 관련 문제 확인"
                )
        );

        RollbackPlan rollbackPlan = new RollbackPlan(
                "상태 악화 시 즉시 실행",
                List.of("이전 커밋으로 롤백", "영향도 재평가")
        );

        return new MitigationPlan(
                context.incidentId(),
                "RULE_BASED_FALLBACK",
                defaultHypotheses,
                defaultActions,
                defaultQuestions,
                rollbackPlan,
                "AI 분석 실패로 인한 기본 계획입니다. 수동 검증이 필수입니다."
        );
    }

    /**
     * 이상 징후 포맷팅
     */
    private String formatAnomalies(List<AnomalyEvent> anomalies) {
        if (anomalies == null || anomalies.isEmpty()) {
            return "이상 징후 없음";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < anomalies.size(); i++) {
            AnomalyEvent anomaly = anomalies.get(i);
            sb.append(String.format("""
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
                    anomaly.baselineValue()
            ));
        }
        return sb.toString();
    }

    /**
     * 증거 항목 포맷팅
     */
    private String formatEvidence(List<EvidenceItem> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "증거 없음";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < evidence.size(); i++) {
            EvidenceItem item = evidence.get(i);
            sb.append(String.format("""
                    [%d] %s (%s)
                        %s
                    """,
                    i + 1,
                    item.title(),
                    item.type(),
                    item.body()
            ));
        }
        return sb.toString();
    }

    /**
     * 메타데이터 포맷팅
     */
    private String formatMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "추가 정보 없음";
        }

        StringBuilder sb = new StringBuilder();
        metadata.forEach((key, value) ->
                sb.append(String.format("- %s: %s\n", key, value))
        );
        return sb.toString();
    }

    /**
     * JSON 응답 파싱 (간단 구현)
     */
    private MitigationPlan parseMitigationPlanJson(String jsonResponse, String incidentId) {
        // Markdown 코드 블록 제거 (ChatGPT가 ```json ... ```으로 감싼는 경우 대응)
        String cleanedResponse = jsonResponse;
        if (cleanedResponse.contains("```")) {
            // 첫 번째 ``` 이후부터 마지막 ``` 이전까지 추출
            int firstBacktick = cleanedResponse.indexOf("```");
            if (firstBacktick != -1) {
                // 첫 번째 라인 끝나는 개행 문자 찾기
                int newlineAfterFirstBlock = cleanedResponse.indexOf("\n", firstBacktick);
                if (newlineAfterFirstBlock != -1) {
                    cleanedResponse = cleanedResponse.substring(newlineAfterFirstBlock + 1);
                }

                // 마지막 ``` 제거
                int lastBacktick = cleanedResponse.lastIndexOf("```");
                if (lastBacktick != -1) {
                    cleanedResponse = cleanedResponse.substring(0, lastBacktick);
                }
            }
        }

        try {
            // Jackson ObjectMapper로 파싱 (가장 안전한 방식)
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            // JSON에서 레코드로 변환을 위한 TypeReference
            var planNode = mapper.readTree(cleanedResponse);

            // hypotheses 파싱
            List<Hypothesis> hypotheses = mapper.convertValue(
                    planNode.get("hypotheses"),
                    mapper.getTypeFactory().constructCollectionType(List.class, Hypothesis.class)
            );

            // actions 파싱
            List<Action> actions = mapper.convertValue(
                    planNode.get("actions"),
                    mapper.getTypeFactory().constructCollectionType(List.class, Action.class)
            );

            // questions 파싱
            List<ClarifyingQuestion> questions = mapper.convertValue(
                    planNode.get("questions"),
                    mapper.getTypeFactory().constructCollectionType(List.class, ClarifyingQuestion.class)
            );

            // rollbackPlan 파싱
            RollbackPlan rollbackPlan = mapper.convertValue(
                    planNode.get("rollbackPlan"),
                    RollbackPlan.class
            );

            return new MitigationPlan(
                    incidentId,
                    "AI_GPT4O_MINI",
                    hypotheses,
                    actions,
                    questions,
                    rollbackPlan,
                    "AI가 생성한 완화 계획입니다. 검증 후 실행을 권장합니다."
            );

        } catch (Exception e) {
            log.error("[AiSre] JSON 파싱 실패, 기본 계획 반환: {}", e.getMessage());
            return new MitigationPlan(
                    incidentId,
                    "AI_PARSE_FAILED",
                    List.of(new Hypothesis("JSON 파싱 실패", "LOW", List.of(e.getMessage()))),
                    List.of(new Action(1, "수동 분석 실행", "LOW", "LLM 응답 확인 필요")),
                    List.of(),
                    new RollbackPlan("즉시", List.of("수동 개입")),
                    "JSON 파싱 실패로 기본 계획을 반환했습니다."
            );
        }
    }

    /**
     * 인시던트 완화 계획 Record
     */
    public record MitigationPlan(
            String incidentId,
            String analysisSource,
            List<Hypothesis> hypotheses,
            List<Action> actions,
            List<ClarifyingQuestion> questions,
            RollbackPlan rollbackPlan,
            String disclaimer
    ) {
    }

    /**
     * 가설 (원인 가설)
     */
    public record Hypothesis(
            String cause,
            String confidence,
            List<String> evidence
    ) {
    }

    /**
     * 조치 항목
     */
    public record Action(
            int step,
            String action,
            String risk,
            String expectedOutcome
    ) {
    }

    /**
     * 명확화 질문
     */
    public record ClarifyingQuestion(
            String question,
            String why
    ) {
    }

    /**
     * 롤백 계획
     */
    public record RollbackPlan(
            String trigger,
            List<String> steps
    ) {
    }
}
