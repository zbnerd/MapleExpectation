package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;

/**
 * Exception thrown when message publishing to queue fails.
 *
 * <p><strong>ServerBaseException (5xx):</strong> Queue publish failure is considered
 * a system/infrastructure error, not a business logic error. This triggers circuit
 * breaker recording to prevent cascading failures.
 *
 * <p><strong>Use Cases:</strong>
 * <ul>
 *   <li>Redis connection failure during publish</li>
 *   <li>Kafka broker unavailable</li>
 *   <li>JSON serialization error</li>
 *   <li>Topic/queue capacity exceeded</li>
 * </ul>
 *
 * <p><strong>Recovery Strategy:</strong>
 * <ul>
 *   <li>Transient failures (connection timeout): Retry with exponential backoff</li>
 *   <li>Persistent failures (queue down): Circuit breaker opens, fallback to local storage</li>
 *   <li>Serialization errors: Fail-fast (indicates data corruption)</li>
 * </ul>
 *
 * @see ServerBaseException
 */
public class QueuePublishException extends ServerBaseException {

  /**
   * Constructs a new queue publish exception with the specified detail message.
   *
   * @param message Detail message (should include topic and event type)
   */
  public QueuePublishException(String message) {
    super(CommonErrorCode.INTERNAL_SERVER_ERROR, message);
  }

  /**
   * Constructs a new queue publish exception with the specified detail message and cause.
   *
   * @param message Detail message
   * @param cause Root cause (e.g., Redis connection exception)
   */
  public QueuePublishException(String message, Throwable cause) {
    super(CommonErrorCode.INTERNAL_SERVER_ERROR, cause, message);
  }
}
