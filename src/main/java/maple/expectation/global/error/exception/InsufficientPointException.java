package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

public class InsufficientPointException extends ClientBaseException implements CircuitBreakerIgnoreMarker {
    public InsufficientPointException(String msg) {
        super(CommonErrorCode.INSUFFICIENT_POINTS, msg);
    }
}