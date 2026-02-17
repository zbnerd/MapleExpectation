package maple.expectation.error.exception;

import maple.expectation.error.ErrorCode;
import maple.expectation.error.exception.base.ServerBaseException;

/**
 * Exception thrown when external API calls fail.
 *
 * <p>This is a server-side exception (5xx) that indicates failures when calling external services
 * such as the Nexon Open API.
 *
 * <p><strong>CLAUDE.md Section 11 Compliance:</strong>
 *
 * <ul>
 *   <li>Extends ServerBaseException for circuit breaker recording
 *   <li>Implements CircuitBreakerRecordMarker for fault tracking
 *   <li>Includes dynamic messages with request IDs for debugging
 * </ul>
 *
 * <h3>Usage Examples:</h3>
 *
 * <pre>
 * throw new ExternalApiException(
 *     ErrorCode.EXTERNAL_API_ERROR,
 *     "Nexon API call failed: %s",
 *     requestId
 * );
 * </pre>
 *
 * @see maple.expectation.infrastructure.external.NexonApiClient
 * @see maple.expectation.service.v2.outbox.NexonApiOutboxProcessor
 * @since 1.0.0
 */
public class ExternalApiException extends ServerBaseException {

  /**
   * Create exception with error code.
   *
   * @param errorCode Error code enum
   */
  public ExternalApiException(ErrorCode errorCode) {
    super(errorCode);
  }

  /**
   * Create exception with error code and dynamic message arguments.
   *
   * @param errorCode Error code enum
   * @param args Message arguments for String.format()
   */
  public ExternalApiException(ErrorCode errorCode, Object... args) {
    super(errorCode, args);
  }

  /**
   * Create exception with error code and root cause.
   *
   * @param errorCode Error code enum
   * @param cause Root cause exception
   */
  public ExternalApiException(ErrorCode errorCode, Throwable cause) {
    super(errorCode, cause);
  }

  /**
   * Create exception with error code, root cause, and dynamic message arguments.
   *
   * @param errorCode Error code enum
   * @param cause Root cause exception
   * @param args Message arguments for String.format()
   */
  public ExternalApiException(ErrorCode errorCode, Throwable cause, Object... args) {
    super(errorCode, cause, args);
  }
}
