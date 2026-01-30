package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * Admin fingerprint 유효성 검증 실패 예외
 *
 * <p>fingerprint가 null이거나 빈 문자열인 경우 발생합니다.</p>
 *
 * <h3>Circuit Breaker</h3>
 * <p>입력 검증 실패이므로 서킷브레이커에 영향 없음 (CircuitBreakerIgnoreMarker)</p>
 */
public class InvalidAdminFingerprintException extends ClientBaseException implements CircuitBreakerIgnoreMarker {

    public InvalidAdminFingerprintException() {
        super(CommonErrorCode.INVALID_INPUT_VALUE, "fingerprint must not be blank");
    }
}
