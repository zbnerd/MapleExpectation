package maple.expectation.service.v2.alert.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Discord Webhook API DTO
 *
 * <p>Discord Webhook API가 요구하는 JSON 형식:
 *
 * <pre>{@code
 * {
 *   "content": "메시지 내용 (optional)",
 *   "embeds": [
 *     {
 *       "title": "제목",
 *       "description": "설명",
 *       "color": 16711680,
 *       "fields": [...],
 *       "footer": {"text": "..."},
 *       "timestamp": "2025-02-12T00:00:00Z"
 *     }
 *   ]
 * }
 * }</pre>
 *
 * @see <a href="https://discord.com/developers/docs/resources/webhook#execute-webhook">Discord Webhook
 *     API</a>
 */
public record DiscordMessage(
    @JsonProperty("embeds") List<Embed> embeds) {

  public record Embed(
      @JsonProperty("title") String title,
      @JsonProperty("description") String description,
      @JsonProperty("color") Integer color,
      @JsonProperty("fields") List<Field> fields,
      @JsonProperty("footer") Footer footer,
      @JsonProperty("timestamp") String timestamp) {}

  public record Field(
      @JsonProperty("name") String name,
      @JsonProperty("value") String value,
      @JsonProperty("inline") boolean inline) {}

  public record Footer(@JsonProperty("text") String text) {}
}
