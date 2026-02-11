package maple.expectation.service.v2.alert;

import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.monitoring.ai.AiSreService;
import maple.expectation.monitoring.ai.AiSreService.AiAnalysisResult;
import maple.expectation.monitoring.context.SystemContextProvider;
import maple.expectation.service.v2.alert.dto.DiscordMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Discord 알림 서비스 (Issue #251 확장)
 *
 * <h3>AI SRE 통합</h3>
 *
 * <ul>
 *   <li>에러 발생 시 AI 분석 자동 트리거
 *   <li>시스템 컨텍스트 자동 수집
 *   <li>AI 분석 실패 시 기본 알림 전송 (Fallback)
 * </ul>
 *
 * <h3>P1-3 Fix: 생성자 주입 (CLAUDE.md Section 6)</h3>
 *
 * <p>@Autowired(required=false) 필드 주입 -> Optional 생성자 주입
 *
 * <h4>CLAUDE.md 준수사항</h4>
 *
 * <ul>
 *   <li>Section 6 (Design Patterns): Factory 패턴 + 생성자 주입
 *   <li>Section 12-1 (Circuit Breaker): AI 서비스 장애 격리
 * </ul>
 */
@Slf4j
@Service
public class DiscordAlertService {

  /**
   * Discord 알림 타임아웃 (3초)
   *
   * <p>Issue #196: Fire-and-Forget 특성상 짧게 설정하여 리소스 점유 최소화
   */
  private static final Duration ALERT_TIMEOUT = Duration.ofSeconds(3);

  private final WebClient webClient;
  private final DiscordMessageFactory messageFactory;
  private final Optional<AiSreService> aiSreService;
  private final Optional<SystemContextProvider> contextProvider;

  @Value("${discord.webhook-url}")
  private String webhookUrl;

  @Value("${ai.sre.enabled:false}")
  private boolean aiSreEnabled;

  public DiscordAlertService(
      @Qualifier("webclient") WebClient webClient,
      DiscordMessageFactory messageFactory,
      Optional<AiSreService> aiSreService,
      Optional<SystemContextProvider> contextProvider) {
    this.webClient = webClient;
    this.messageFactory = messageFactory;
    this.aiSreService = aiSreService;
    this.contextProvider = contextProvider;
  }

  /** Critical Alert 전송 (기존 호환) */
  public void sendCriticalAlert(String title, String description, Throwable e) {
    if (aiSreEnabled && aiSreService.isPresent()) {
      sendCriticalAlertWithAi(title, description, e);
      return;
    }

    DiscordMessage payload = messageFactory.createCriticalEmbed(title, description, e);
    send(payload);
  }

  /** AI 분석이 포함된 Critical Alert 전송 (Issue #251) */
  public void sendCriticalAlertWithAi(String title, String description, Throwable e) {
    String systemSummary = getSystemSummary();
    Optional<AiAnalysisResult> aiAnalysis = getAiAnalysis(e);

    DiscordMessage payload =
        messageFactory.createCriticalEmbedWithAi(title, description, e, aiAnalysis, systemSummary);
    send(payload);

    logAiAnalysisResult(e, aiAnalysis);
  }

  /** AI 분석 수행 (타임아웃 처리 포함) */
  private Optional<AiAnalysisResult> getAiAnalysis(Throwable e) {
    return aiSreService.flatMap(service -> service.analyzeError(e));
  }

  /** 시스템 컨텍스트 요약 수집 */
  private String getSystemSummary() {
    return contextProvider.map(SystemContextProvider::buildSummary).orElse("");
  }

  /** AI 분석 결과 로깅 */
  private void logAiAnalysisResult(Throwable e, Optional<AiAnalysisResult> aiAnalysis) {
    aiAnalysis.ifPresentOrElse(
        analysis ->
            log.info(
                "[AiSre] 분석 완료: {} -> {} ({})",
                e.getClass().getSimpleName(),
                analysis.severity(),
                analysis.analysisSource()),
        () -> log.debug("[AiSre] AI 분석 스킵 또는 실패: {}", e.getClass().getSimpleName()));
  }

  private void send(DiscordMessage payload) {
    String maskedUrl = webhookUrl.substring(0, Math.min(webhookUrl.length(), 20)) + "...";

    webClient
        .post()
        .uri(webhookUrl)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(payload)
        .retrieve()
        .toBodilessEntity()
        .timeout(ALERT_TIMEOUT)
        .subscribe(
            response -> log.info("[Discord] Alert sent successfully to {}", maskedUrl),
            error -> log.error("[Discord] Failed to send alert: {}", error.getMessage()));
  }
}
