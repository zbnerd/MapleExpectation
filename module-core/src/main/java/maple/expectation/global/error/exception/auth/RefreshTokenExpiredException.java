package maple.expectation.global.error.exception.auth;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * Refresh Token 만료 예외 (Issue #279)
 *
 * <p>발생 조건:
 *
 * <ul>
 *   <li>Refresh Token TTL(7일) 초과
 * </ul>
 */
public class RefreshTokenExpiredException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  public RefreshTokenExpiredException() {
    super(CommonErrorCode.REFRESH_TOKEN_EXPIRED);
  }
}
