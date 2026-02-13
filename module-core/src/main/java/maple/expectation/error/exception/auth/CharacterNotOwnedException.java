package maple.expectation.error.exception.auth;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

/** 캐릭터 소유권 검증 실패 예외 */
public class CharacterNotOwnedException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  public CharacterNotOwnedException(String userIgn) {
    super(CommonErrorCode.CHARACTER_NOT_OWNED, userIgn);
  }
}
