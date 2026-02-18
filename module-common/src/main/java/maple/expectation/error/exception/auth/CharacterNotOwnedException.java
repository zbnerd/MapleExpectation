package maple.expectation.error.exception.auth;

/**
 * Character not owned exception
 *
 * <p>Thrown when a user tries to access a character they don't own.
 */
public class CharacterNotOwnedException extends RuntimeException {
  public CharacterNotOwnedException(String userIgn, String characterOcid) {
    super("User " + userIgn + " doesn't own character with OCID: " + characterOcid);
  }

  public CharacterNotOwnedException(String userIgn, String characterOcid, Throwable cause) {
    super("User " + userIgn + " doesn't own character with OCID: " + characterOcid, cause);
  }
}
