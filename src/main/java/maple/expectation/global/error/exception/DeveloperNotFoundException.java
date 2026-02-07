package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

public class DeveloperNotFoundException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {
  public DeveloperNotFoundException(String developerId) {
    super(CommonErrorCode.DEVELOPER_NOT_FOUND, developerId);
  }
}
