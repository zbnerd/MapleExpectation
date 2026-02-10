package maple.expectation.monitoring;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.ExpectationApplication;
import maple.expectation.monitoring.ai.AiSreService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * AI SRE Service Integration Test
 *
 * <p>실제 디스코드 웹훅과 Z.ai API를 사용하는 통합 테스트
 *
 * <p><b>실행 전제조건:</b>
 *
 * <ul>
 *   <li>MySQL Docker 컨테이너 실행 중: {@code docker-compose up -d}
 *   <li>환경변수 설정: DB_SCHEMA_NAME, DB_ROOT_PASSWORD
 *   <li>선택사항: DISCORD_WEBHOOK_URL (디스코드 알림 전송용)
 * </ul>
 *
 * <p><b>실행 방법:</b>
 *
 * <pre>
 * ./gradlew test --tests "*AiSreService*" --tags "integration"
 * </pre>
 */
@Slf4j
@Tag("integration")
@SpringBootTest(
    classes = ExpectationApplication.class,
    properties = {"spring.batch.job.enabled=false"})
@ActiveProfiles("test")
public class AiSreServiceIntegrationTest {

  @Autowired(required = false)
  private AiSreService aiSreService;

  @Test
  public void testAiSreWithErrorAnalysis() {
    log.info("=== AI SRE Service Integration Test 시작 ===");

    if (aiSreService == null) {
      log.warn("❌ AiSreService가 활성화되지 않았습니다.");
      log.warn("환경변수 설정: AI_SRE_ENABLED=true");
      return;
    }

    log.info("✅ AiSreService가 로드되었습니다.");

    // 테스트용 예외 생성
    SQLException testException =
        new SQLException("Connection timeout: could not connect to database within 5000ms");

    // 비동기 AI 분석 요청
    log.info("🤖 AI 에러 분석 요청 중...");
    CompletableFuture<Optional<AiSreService.AiAnalysisResult>> future =
        aiSreService.analyzeErrorAsync(testException);

    // 결과 대기
    future
        .thenAccept(
            result -> {
              log.info("=== AI 분석 결과 수신 ===");
              result.ifPresent(
                  analysis -> {
                    log.info("📊 원인: {}", analysis.rootCause());
                    log.info("⚠️ 심각도: {}", analysis.severity());
                    log.info("🎯 영향 컴포넌트: {}", analysis.affectedComponents());
                    log.info("🔧 조치사항:\n{}", analysis.actionItems());
                    log.info("📌 분석 출처: {}", analysis.analysisSource());
                    log.info("⚠️ Disclaimer: {}", analysis.disclaimer());

                    // 디스코드로도 전송
                    sendToDiscord(analysis);
                  });

              log.info("=== 테스트 완료 ===");
            })
        .join();
  }

  private void sendToDiscord(AiSreService.AiAnalysisResult analysis) {
    try {
      String webhookUrl = System.getenv("DISCORD_WEBHOOK_URL");
      if (webhookUrl == null || webhookUrl.isBlank()) {
        log.warn("❌ DISCORD_WEBHOOK_URL 환경변수가 설정되지 않았습니다.");
        return;
      }

      String message =
          String.format(
              "🤖 **AI SRE 분석 결과**\n\n"
                  + "**원인:** %s\n"
                  + "**심각도:** %s\n"
                  + "**영향 컴포넌트:** %s\n"
                  + "**조치사항:**\n%s\n\n"
                  + "📌 분석 출처: %s",
              analysis.rootCause(),
              analysis.severity(),
              analysis.affectedComponents(),
              analysis.actionItems(),
              analysis.analysisSource());

      // 웹훅 전송
      java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
      java.net.http.HttpRequest request =
          java.net.http.HttpRequest.newBuilder()
              .uri(java.net.URI.create(webhookUrl))
              .header("Content-Type", "application/json")
              .POST(
                  java.net.http.HttpRequest.BodyPublishers.ofString(
                      String.format("{\"content\": \"%s\"}", message.replace("\n", "\\n"))))
              .build();

      java.net.http.HttpResponse<String> response =
          client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        log.info("✅ 디스코드 알림 전송 성공!");
      } else {
        log.warn("⚠️ 디스코드 알림 전송 실패: HTTP {}", response.statusCode());
      }

    } catch (Exception e) {
      log.error("❌ 디스코드 전송 중 에러 발생", e);
    }
  }
}
