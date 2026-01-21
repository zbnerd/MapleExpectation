package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 유효하지 않은 잠재능력 등급 예외
 *
 * <p>CubeCostPolicy에서 잘못된 등급명 입력 시 발생합니다.
 * CircuitBreakerIgnoreMarker를 구현하여 서킷브레이커에 영향을 주지 않습니다.</p>
 *
 * @see maple.expectation.domain.v2.PotentialGrade
 */
public class InvalidPotentialGradeException extends ClientBaseException
        implements CircuitBreakerIgnoreMarker {

    public InvalidPotentialGradeException(String grade) {
        super(CommonErrorCode.INVALID_INPUT_VALUE, "잠재능력 등급: " + grade);
    }
}
