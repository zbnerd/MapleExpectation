package maple.expectation.core.port.out;

import maple.expectation.core.domain.model.AlertMessage;
import maple.expectation.core.domain.model.AlertPriority;

/**
 * Port for sending alert notifications.
 *
 * <p>Implemented by module-infra adapters (Discord, email, etc.).
 *
 * <p>This interface abstracts the notification sending mechanism, allowing core business logic to
 * remain independent of alert infrastructure.
 */
public interface AlertPort {

  /**
   * Send an alert message.
   *
   * @param message the alert message to send
   * @return true if sent successfully, false otherwise
   */
  boolean sendAlert(AlertMessage message);

  /**
   * Send an alert message with priority.
   *
   * @param message the alert message to send
   * @param priority the priority level (HIGH, MEDIUM, LOW)
   * @return true if sent successfully, false otherwise
   */
  boolean sendAlert(AlertMessage message, AlertPriority priority);
}
