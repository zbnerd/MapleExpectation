package maple.expectation.global.error.exception.auth;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/** 유효하지 않은 API Key 예외 */
public class InvalidApiKeyException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  public InvalidApiKeyException() {
    super(CommonErrorCode.INVALID_API_KEY);
  }
}
