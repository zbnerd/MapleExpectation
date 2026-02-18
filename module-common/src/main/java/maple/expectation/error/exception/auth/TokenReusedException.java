package maple.expectation.error.exception.auth;

/**
 * Token reused exception
 *
 * <p>Thrown when a refresh token is being reused (potential security issue).
 */
public class TokenReusedException extends RuntimeException {
  public TokenReusedException(String tokenId) {
    super("Refresh token reused (potential security breach): " + tokenId);
  }

  public TokenReusedException(String tokenId, Throwable cause) {
    super("Refresh token reused (potential security breach): " + tokenId, cause);
  }
}
