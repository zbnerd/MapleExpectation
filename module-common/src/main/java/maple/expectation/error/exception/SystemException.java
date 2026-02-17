package maple.expectation.error.exception;

import maple.expectation.error.ErrorCode;
import maple.expectation.error.exception.base.ServerBaseException;

/**
 * Exception thrown when a critical system component is unavailable.
 *
 * <p>This is a server-side exception (5xx) that indicates fatal system failures such as missing JVM
 * algorithms or other critical infrastructure issues.
 *
 * <p><strong>CLAUDE.md Section 11 Compliance:</strong>
 *
 * <ul>
 *   <li>Extends ServerBaseException for circuit breaker recording
 *   <li>Implements CircuitBreakerRecordMarker for fault tracking
 *   <li>Includes dynamic messages for debugging
 * </ul>
 *
 * <h3>Usage Examples:</h3>
 *
 * <pre>
 * throw new SystemException(
 *     ErrorCode.INTERNAL_SERVER_ERROR,
 *     "SHA-256 not available",
 *     cause
 * );
 * </pre>
 *
 * @see maple.expectation.infrastructure.concurrency.DistributedSingleFlightExecutor
 * @see maple.expectation.domain.v2.NexonApiOutbox
 * @since 1.0.0
 */
public class SystemException extends ServerBaseException {

  /**
   * Create exception with error code.
   *
   * @param errorCode Error code enum
   */
  public SystemException(ErrorCode errorCode) {
    super(errorCode);
  }

  /**
   * Create exception with error code and dynamic message arguments.
   *
   * @param errorCode Error code enum
   * @param args Message arguments for String.format()
   */
  public SystemException(ErrorCode errorCode, Object... args) {
    super(errorCode, args);
  }

  /**
   * Create exception with error code and root cause.
   *
   * @param errorCode Error code enum
   * @param cause Root cause exception
   */
  public SystemException(ErrorCode errorCode, Throwable cause) {
    super(errorCode, cause);
  }

  /**
   * Create exception with error code, root cause, and dynamic message arguments.
   *
   * @param errorCode Error code enum
   * @param cause Root cause exception
   * @param args Message arguments for String.format()
   */
  public SystemException(ErrorCode errorCode, Throwable cause, Object... args) {
    super(errorCode, cause, args);
  }
}
