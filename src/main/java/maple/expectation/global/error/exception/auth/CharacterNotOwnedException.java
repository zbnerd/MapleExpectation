package maple.expectation.global.error.exception.auth;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 캐릭터 소유권 검증 실패 예외
 */
public class CharacterNotOwnedException extends ClientBaseException implements CircuitBreakerIgnoreMarker {

    public CharacterNotOwnedException(String userIgn) {
        super(CommonErrorCode.CHARACTER_NOT_OWNED, userIgn);
    }
}
