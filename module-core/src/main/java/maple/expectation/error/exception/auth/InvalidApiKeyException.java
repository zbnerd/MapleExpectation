package maple.expectation.error.exception.auth;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

/** 유효하지 않은 API Key 예외 */
public class InvalidApiKeyException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  public InvalidApiKeyException() {
    super(CommonErrorCode.INVALID_API_KEY);
  }
}
