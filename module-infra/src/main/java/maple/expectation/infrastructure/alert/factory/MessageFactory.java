package maple.expectation.infrastructure.alert.factory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.alert.message.AlertMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Discord Message Factory
 *
 * <p>Converts AlertMessage to Discord webhook payload format
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@Slf4j
public class MessageFactory {

  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final int COLOR_ERROR = 0xFF0000; // Red
  private static final int COLOR_INFO = 0x00FF00; // Green

  /** Convert AlertMessage to Discord JSON payload */
  public static String toDiscordPayload(AlertMessage message) {
    try {
      DiscordPayload payload = buildDiscordPayload(message);
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      log.error("[MessageFactory] Failed to serialize Discord payload: {}", e.getMessage(), e);
      return buildFallbackPayload(message);
    }
  }

  /** Build Discord payload object from AlertMessage */
  private static DiscordPayload buildDiscordPayload(AlertMessage message) {
    // Build description
    StringBuilder description = new StringBuilder(message.getMessage());

    // Add error details if present
    if (message.getError() != null) {
      description.append("\n\n**Error:**\n```\n");
      description.append(message.getError().toString());
      description.append("\n```");
    }

    // Build embed
    Embed embed =
        new Embed(
            message.getTitle(),
            description.toString(),
            message.getError() != null ? COLOR_ERROR : COLOR_INFO,
            Collections.emptyList(),
            new Footer("MapleExpectation Alert System"),
            Instant.now().toString());

    // Discord API requires either content OR embeds (not both)
    // When using embeds, content should be null or empty string
    return new DiscordPayload("", Collections.singletonList(embed));
  }

  /** Fallback payload for serialization failure */
  private static String buildFallbackPayload(AlertMessage message) {
    return String.format(
        "{\"content\":\"**%s**\\n%s\"}",
        escapeJson(message.getTitle()), escapeJson(message.getMessage()));
  }

  /** Escape special JSON characters */
  private static String escapeJson(String text) {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  /** Create HTTP headers for Discord webhook */
  public static HttpHeaders createDiscordHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  /** Discord Webhook API payload structure */
  private record DiscordPayload(
      @JsonProperty("content") String content, @JsonProperty("embeds") List<Embed> embeds) {}

  /** Discord Embed structure */
  public record Embed(
      String title,
      String description,
      int color,
      List<Field> fields,
      Footer footer,
      String timestamp) {}

  /** Discord Field structure */
  public record Field(String name, String value, boolean inline) {}

  /** Discord Footer structure */
  public record Footer(String text, String iconUrl) {
    public Footer(String text) {
      this(text, null);
    }
  }
}
