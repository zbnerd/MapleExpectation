package maple.expectation.error.exception.auth;

/**
 * Session not found exception
 *
 * <p>Thrown when a requested session doesn't exist or has expired.
 */
public class SessionNotFoundException extends RuntimeException {
  public SessionNotFoundException(String sessionId) {
    super("Session not found: " + sessionId);
  }

  public SessionNotFoundException(String sessionId, Throwable cause) {
    super("Session not found: " + sessionId, cause);
  }
}
