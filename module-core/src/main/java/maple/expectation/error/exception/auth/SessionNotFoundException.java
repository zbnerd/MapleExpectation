package maple.expectation.error.exception.auth;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 세션 없음 예외 (Issue #279)
 *
 * <p>발생 조건:
 *
 * <ul>
 *   <li>Refresh Token은 유효하나 연결된 세션이 만료됨
 *   <li>세션 TTL(30분) 초과 후 Refresh 시도
 * </ul>
 *
 * <p>처리 방안:
 *
 * <ul>
 *   <li>사용자에게 재로그인 안내
 * </ul>
 */
public class SessionNotFoundException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  public SessionNotFoundException() {
    super(CommonErrorCode.SESSION_NOT_FOUND);
  }
}
