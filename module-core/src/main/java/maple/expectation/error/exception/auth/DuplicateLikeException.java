package maple.expectation.error.exception.auth;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

/** 중복 좋아요 예외 */
public class DuplicateLikeException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  public DuplicateLikeException() {
    super(CommonErrorCode.DUPLICATE_LIKE);
  }
}
