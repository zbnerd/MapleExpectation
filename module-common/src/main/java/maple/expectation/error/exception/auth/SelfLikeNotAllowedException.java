package maple.expectation.error.exception.auth;

/**
 * Self like not allowed exception
 *
 * <p>Thrown when a user tries to like their own character.
 */
public class SelfLikeNotAllowedException extends RuntimeException {
  public SelfLikeNotAllowedException(String userIgn, String targetIgn) {
    super("User cannot like their own character: " + userIgn + " -> " + targetIgn);
  }

  public SelfLikeNotAllowedException(String userIgn, String targetIgn, Throwable cause) {
    super("User cannot like their own character: " + userIgn + " -> " + targetIgn, cause);
  }
}
