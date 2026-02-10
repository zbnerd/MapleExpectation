package maple.expectation.global.error.exception.auth;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/** Self-Like 금지 예외 */
public class SelfLikeNotAllowedException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  public SelfLikeNotAllowedException() {
    super(CommonErrorCode.SELF_LIKE_NOT_ALLOWED);
  }
}
