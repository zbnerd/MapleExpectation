package maple.expectation.service.v2.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.service.v2.alert.dto.DiscordMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordAlertService {

    private final WebClient webClient;
    private final DiscordMessageFactory messageFactory;

    @Value("${discord.webhook-url}")
    private String webhookUrl;

    public void sendCriticalAlert(String title, String description, Exception e) {
        // 1. 메시지 생성은 Factory에게 위임
        DiscordMessage payload = messageFactory.createCriticalEmbed(title, description, e);

        // 2. 전송 실행
        send(payload);
    }

    private void send(DiscordMessage payload) {
        // 보안을 위한 URL 마스킹 로직
        String maskedUrl = webhookUrl.substring(0, Math.min(webhookUrl.length(), 20)) + "...";

        webClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                        response -> log.info("✅ Discord Alert Sent successfully to {}", maskedUrl),
                        error -> log.error("❌ Failed to send Discord Alert: {}", error.getMessage())
                );
    }
}