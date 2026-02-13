package maple.expectation.error.exception.auth;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

/** Self-Like 금지 예외 */
public class SelfLikeNotAllowedException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  public SelfLikeNotAllowedException() {
    super(CommonErrorCode.SELF_LIKE_NOT_ALLOWED);
  }
}
