package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

public class CharacterNotFoundException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {
  public CharacterNotFoundException(String userIgn) {
    super(CommonErrorCode.CHARACTER_NOT_FOUND, userIgn);
  }
}
