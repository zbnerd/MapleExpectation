package maple.expectation.error.exception;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

public class DeveloperNotFoundException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {
  public DeveloperNotFoundException(String developerId) {
    super(CommonErrorCode.DEVELOPER_NOT_FOUND, developerId);
  }
}
