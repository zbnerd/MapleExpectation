package maple.expectation.error.exception;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;

/**
 * Invalid admin fingerprint exception
 *
 * <p>Thrown when an admin's fingerprint doesn't match the expected value for security verification
 * purposes.
 */
public class InvalidAdminFingerprintException extends ClientBaseException {

  public InvalidAdminFingerprintException() {
    super(CommonErrorCode.FORBIDDEN);
  }

  public InvalidAdminFingerprintException(String message) {
    super(CommonErrorCode.FORBIDDEN, message);
  }

  public InvalidAdminFingerprintException(String message, Throwable cause) {
    super(CommonErrorCode.FORBIDDEN, cause, message);
  }
}
