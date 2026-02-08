package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;

/**
 * Exception thrown when observability tracking fails.
 *
 * <p>This exception indicates a failure in metrics collection, logging, or monitoring
 * infrastructure. It is a checked exception wrapper for underlying observability system errors.
 *
 * <p><strong>Retry Policy:</strong> Observability failures should typically use {@code try-catch}
 * with fallback behavior (e.g., log to file instead of remote metrics).
 *
 * <h3>Usage Example:</h3>
 *
 * <pre>{@code
 * // In ObservabilityAspect or similar
 * try {
 *     metricsRegistry.counter(metricName).increment();
 * } catch (Exception e) {
 *     throw new ObservabilityException("Failed to record metric: " + metricName, e);
 * }
 * }</pre>
 *
 * @see maple.expectation.global.error.exception.base.ServerBaseException
 */
public class ObservabilityException extends ServerBaseException {

  /**
   * Constructs a new observability exception with the specified detail message.
   *
   * @param message the detail message (should contain context about what observability operation
   *     failed)
   */
  public ObservabilityException(String message) {
    super(CommonErrorCode.INTERNAL_SERVER_ERROR, message);
  }

  /**
   * Constructs a new observability exception with the specified detail message and cause.
   *
   * @param message the detail message (should contain context about what observability operation
   *     failed)
   * @param cause the cause (the underlying throwable that caused this exception)
   */
  public ObservabilityException(String message, Throwable cause) {
    super(CommonErrorCode.INTERNAL_SERVER_ERROR, cause, message);
  }
}
