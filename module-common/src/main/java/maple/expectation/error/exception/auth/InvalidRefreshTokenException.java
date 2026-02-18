package maple.expectation.error.exception.auth;

/**
 * Invalid refresh token exception
 *
 * <p>Thrown when a refresh token is invalid, malformed, or tampered with.
 */
public class InvalidRefreshTokenException extends RuntimeException {
  public InvalidRefreshTokenException(String tokenId) {
    super("Invalid refresh token: " + tokenId);
  }

  public InvalidRefreshTokenException(String tokenId, Throwable cause) {
    super("Invalid refresh token: " + tokenId, cause);
  }
}
