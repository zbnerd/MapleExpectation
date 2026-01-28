package maple.expectation.global.error.exception.auth;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 유효하지 않은 Refresh Token 예외 (Issue #279)
 *
 * <p>발생 조건:
 * <ul>
 *   <li>존재하지 않는 Refresh Token ID</li>
 *   <li>형식이 잘못된 Refresh Token</li>
 * </ul>
 * </p>
 */
public class InvalidRefreshTokenException extends ClientBaseException
        implements CircuitBreakerIgnoreMarker {

    public InvalidRefreshTokenException() {
        super(CommonErrorCode.INVALID_REFRESH_TOKEN);
    }
}
