package maple.expectation.error.exception.auth;

/**
 * Refresh token expired exception
 *
 * <p>Thrown when a refresh token has expired and can no longer be used.
 */
public class RefreshTokenExpiredException extends RuntimeException {
  public RefreshTokenExpiredException(String tokenId) {
    super("Refresh token expired: " + tokenId);
  }

  public RefreshTokenExpiredException(String tokenId, Throwable cause) {
    super("Refresh token expired: " + tokenId, cause);
  }
}
