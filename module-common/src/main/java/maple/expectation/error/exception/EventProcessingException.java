package maple.expectation.error.exception;

import maple.expectation.error.ErrorCode;
import maple.expectation.error.exception.base.ServerBaseException;

/**
 * Exception thrown when event processing fails.
 *
 * <p>This is a server-side exception (5xx) that indicates system failure during event handling.
 * Used by RedisStreamEventConsumer, EventDispatcher, and event handlers.
 *
 * <p><strong>CLAUDE.md Section 11 Compliance:</strong>
 *
 * <ul>
 *   <li>Extends ServerBaseException for circuit breaker recording
 *   <li>Implements CircuitBreakerRecordMarker for fault tracking
 *   <li>Includes dynamic messages with event IDs for debugging
 * </ul>
 *
 * <h3>Usage Examples:</h3>
 *
 * <pre>
 * // Consumer failure
 * throw new EventProcessingException(
 *     ErrorCode.EVENT_CONSUMER_ERROR,
 *     "Failed to read from stream: integration-events"
 * );
 *
 * // Handler failure
 * throw new EventProcessingException(
 *     ErrorCode.EVENT_HANDLER_ERROR,
 *     "Handler failed for eventId=%s, eventType=%s",
 *     eventId, eventType
 * );
 * </pre>
 *
 * @see maple.expectation.infrastructure.messaging.RedisStreamEventConsumer
 * @see maple.expectation.event.EventDispatcher
 * @since 1.0.0
 */
public class EventProcessingException extends ServerBaseException {

  /**
   * Create exception with error code.
   *
   * @param errorCode Error code enum
   */
  public EventProcessingException(ErrorCode errorCode) {
    super(errorCode);
  }

  /**
   * Create exception with error code and dynamic message arguments.
   *
   * @param errorCode Error code enum
   * @param args Message arguments for String.format()
   */
  public EventProcessingException(ErrorCode errorCode, Object... args) {
    super(errorCode, args);
  }

  /**
   * Create exception with error code and root cause.
   *
   * @param errorCode Error code enum
   * @param cause Root cause exception
   */
  public EventProcessingException(ErrorCode errorCode, Throwable cause) {
    super(errorCode, cause);
  }

  /**
   * Create exception with error code, root cause, and dynamic message arguments.
   *
   * @param errorCode Error code enum
   * @param cause Root cause exception
   * @param args Message arguments for String.format()
   */
  public EventProcessingException(ErrorCode errorCode, Throwable cause, Object... args) {
    super(errorCode, cause, args);
  }
}
