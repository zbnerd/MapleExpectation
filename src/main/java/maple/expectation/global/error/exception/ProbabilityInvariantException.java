package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 확률 불변식 위반 예외
 *
 * <p>다음 경우에 발생:</p>
 * <ul>
 *   <li>질량 보존 위반: Σp ≠ 1 ± tolerance</li>
 *   <li>음수 확률 감지</li>
 *   <li>확률 > 1 감지</li>
 *   <li>NaN/Inf 감지</li>
 *   <li>테이블 비어있음</li>
 * </ul>
 */
public class ProbabilityInvariantException extends ServerBaseException implements CircuitBreakerIgnoreMarker {

    public ProbabilityInvariantException(String detail) {
        super(CommonErrorCode.DATA_PROCESSING_ERROR, "확률 불변식 위반: " + detail);
    }

    public ProbabilityInvariantException(String detail, Throwable cause) {
        super(CommonErrorCode.DATA_PROCESSING_ERROR, cause, "확률 불변식 위반: " + detail);
    }
}
