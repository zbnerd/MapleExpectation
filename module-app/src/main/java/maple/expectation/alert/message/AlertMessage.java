package maple.expectation.alert.message;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Alert Message DTO
 *
 * <p>Immutable data transfer object for alert messages
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
@Data
@AllArgsConstructor
public class AlertMessage {

  private String title;
  private String message;
  private Throwable error;
  private String webhookUrl;

  public String getTitle() {
    return title;
  }

  public String getMessage() {
    return message;
  }

  public Throwable getError() {
    return error;
  }

  public String getWebhookUrl() {
    return webhookUrl;
  }

  public String getFormattedMessage() {
    if (error != null) {
      return String.format("**%s**\n```\n%s", message, error.toString());
    }
    return String.format("**%s**", message);
  }
}
