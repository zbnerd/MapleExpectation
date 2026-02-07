package maple.expectation.global.error.exception.auth;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 이미 사용된 토큰 재사용 예외 (Issue #279)
 *
 * <p>발생 조건:
 *
 * <ul>
 *   <li>Token Rotation으로 이미 사용 처리된 Refresh Token 재사용 시도
 *   <li>탈취 의심 상황 - 해당 Family의 모든 토큰이 무효화됨
 * </ul>
 *
 * <p>보안 조치:
 *
 * <ul>
 *   <li>동일 familyId의 모든 Refresh Token 무효화
 *   <li>사용자 재로그인 강제
 * </ul>
 */
public class TokenReusedException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  public TokenReusedException() {
    super(CommonErrorCode.TOKEN_REUSED);
  }
}
