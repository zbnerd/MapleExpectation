package maple.expectation.alert.factory;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.alert.message.AlertMessage;
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

  /** Convert AlertMessage to Discord JSON payload */
  public static String toDiscordPayload(AlertMessage message) {
    StringBuilder content = new StringBuilder();

    // Title
    content.append("## ");
    content.append(message.getTitle());
    content.append("\n");

    // Message
    content.append(message.getMessage());
    content.append("\n");

    // Error details (if any)
    if (message.getError() != null) {
      content.append("### Error Details\n```\n");
      content.append("```");
      content.append(message.getError().toString());
      content.append("```");
    }

    return content.toString();
  }

  /** Create HTTP headers for Discord webhook */
  public static HttpHeaders createDiscordHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }
}
