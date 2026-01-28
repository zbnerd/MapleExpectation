package maple.expectation.monitoring.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.monitoring.context.SystemContextProvider;
import maple.expectation.monitoring.security.PiiMaskingFilter;
import maple.expectation.monitoring.throttle.AlertThrottler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.sre.enabled", havingValue = "true")
public class AiSreService {

    private final ChatModel chatModel;
    private final SystemContextProvider contextProvider;
    private final PiiMaskingFilter piiFilter;
    private final AlertThrottler throttler;
    private final LogicExecutor executor;

    // [P0-Green] Virtual Thread Executor for LLM 지연 격리
    private final Executor aiExecutor = Executors.newVirtualThreadPerTaskExecutor();

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

    /**
     * 에러 분석 수행 (비동기)
     *
     * @param exception 분석할 예외
     * @return AI 분석 결과 (Optional - 실패 시 empty)
     */
    public CompletableFuture<Optional<AiAnalysisResult>> analyzeErrorAsync(Throwable exception) {
        return CompletableFuture.supplyAsync(
                () -> analyzeError(exception),
                aiExecutor
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

        // 4. LLM 호출 (ChatModel.chat() 사용)
        String response = chatModel.chat(SYSTEM_PROMPT + "\n\n" + prompt.text());

        // 5. 결과 파싱
        return Optional.of(parseAiResponse(response, exception));
    }

    /**
     * Fallback: 규칙 기반 분석 (Circuit Breaker Open 또는 LLM 실패 시)
     */
    @SuppressWarnings("unused")
    private Optional<AiAnalysisResult> fallbackAnalysis(Throwable exception, Throwable cause) {
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
}
