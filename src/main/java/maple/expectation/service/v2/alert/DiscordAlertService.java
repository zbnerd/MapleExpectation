package maple.expectation.service.v2.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Service
public class DiscordAlertService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // application-prod.yml에서 주입받을 Webhook URL
    @Value("${discord.webhook-url}")
    private String webhookUrl;

    public DiscordAlertService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Discord로 알림 메시지를 전송합니다. (비동기 처리)
     * @param title 알림 제목
     * @param description 알림 내용
     * @param e 발생한 예외 객체
     */
    public void sendCriticalAlert(String title, String description, Exception e) {
        try {
            String jsonPayload = createDiscordPayload(title, description, e);

            // 💡 보안상 Webhook URL 마스킹 처리 (앞 20자 + ... + 뒤 8자만 노출)
            String maskedUrl = webhookUrl.substring(0, Math.min(webhookUrl.length(), 20)) + "..." +
                    webhookUrl.substring(Math.max(0, webhookUrl.length() - 8));

            webClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(jsonPayload)
                    .retrieve()
                    .toBodilessEntity()
                    .subscribe(
                            // 💡 마스킹된 URL 로그 출력
                            response -> log.info("Discord Alert Sent successfully to {}", maskedUrl),
                            error -> log.error("Failed to send Discord Alert. Reason: {}", error.getMessage())
                    );
        } catch (Exception ex) {
            log.error("Failed to create Discord payload or send request.", ex);
        }
    }

    /**
     * Discord Webhook JSON Payload (Embed 형식)을 생성합니다.
     * @return JSON 문자열
     */
    private String createDiscordPayload(String title, String description, Exception e) throws Exception {
        
        // 스택 트레이스의 첫 5줄만 포함하여 메시지가 너무 길어지는 것을 방지
        String stackTrace = org.springframework.util.StringUtils.arrayToDelimitedString(
                java.util.Arrays.stream(e.getStackTrace()).limit(5).toArray(), "\n");

        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);

        // Discord Embed 구조를 Map으로 정의
        Map<String, Object> embed = Map.of(
            "title", "🚨 " + title,
            "description", description,
            "color", 16711680, // 빨간색 (RBG: FF0000)
            "fields", new Object[]{
                Map.of("name", "⏰ Timestamp (KST)", "value", timestamp, "inline", true),
                Map.of("name", "📄 Exception Type", "value", e.getClass().getSimpleName(), "inline", true),
                Map.of("name", "💻 Server IP", "value", System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME") : "Unknown", "inline", true),
                Map.of("name", "💬 Root Cause", "value", e.getMessage() != null ? e.getMessage() : "Null Message", "inline", false),
                Map.of("name", "Stack Trace (Top 5)", "value", "```java\n" + stackTrace + "\n```", "inline", false)
            },
            "footer", Map.of("text", "MapleExpectation Alert System")
        );
        
        // 최종 Payload
        Map<String, Object> payload = Map.of("embeds", new Object[]{embed});

        return objectMapper.writeValueAsString(payload);
    }
}