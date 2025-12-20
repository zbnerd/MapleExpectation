package maple.expectation.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.ClientBaseException;

public class InsufficientPointException extends ClientBaseException {
    public InsufficientPointException(String msg) {
        super(CommonErrorCode.INSUFFICIENT_POINTS, msg);
    }
}