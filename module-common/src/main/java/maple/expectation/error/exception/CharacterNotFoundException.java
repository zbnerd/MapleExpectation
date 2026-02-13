package maple.expectation.error.exception;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

public class CharacterNotFoundException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {
  public CharacterNotFoundException(String userIgn) {
    super(CommonErrorCode.CHARACTER_NOT_FOUND, userIgn);
  }
}
