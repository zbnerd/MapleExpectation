package maple.expectation.core.domain.model;

/**
 * Alert message domain model.
 *
 * <p>Represents an alert notification message.
 *
 * <p>Pure domain model - no external dependencies.
 *
 * @param title the alert title
 * @param message the alert message content
 * @param error the associated error (optional)
 */
public record AlertMessage(String title, String message, Throwable error) {
  /** Validates the alert message. */
  public AlertMessage {
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("title cannot be null or blank");
    }
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message cannot be null or blank");
    }
  }

  /** Create an alert message without error. */
  public static AlertMessage of(String title, String message) {
    return new AlertMessage(title, message, null);
  }

  /** Create an alert message with error. */
  public static AlertMessage withError(String title, String message, Throwable error) {
    return new AlertMessage(title, message, error);
  }

  /**
   * Get formatted message with error details if present.
   *
   * @return the formatted message
   */
  public String getFormattedMessage() {
    if (error != null) {
      return String.format("**%s**\n```\n%s", message, error.toString());
    }
    return String.format("**%s**", message);
  }
}
