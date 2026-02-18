package maple.expectation.error.exception;

/**
 * Invalid admin fingerprint exception
 *
 * <p>Thrown when an admin's fingerprint doesn't match the expected value for security verification
 * purposes.
 */
public class InvalidAdminFingerprintException extends RuntimeException {
  public InvalidAdminFingerprintException(String message) {
    super(message);
  }

  public InvalidAdminFingerprintException(String message, Throwable cause) {
    super(message, cause);
  }
}
