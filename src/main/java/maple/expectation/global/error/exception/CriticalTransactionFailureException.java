package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

public class CriticalTransactionFailureException extends ServerBaseException implements CircuitBreakerIgnoreMarker {
    public CriticalTransactionFailureException(String detail, Throwable cause) {
        super(CommonErrorCode.DATABASE_TRANSACTION_FAILURE, detail);
        // 필요 시 별도로 cause를 로깅하거나 상위로 전달 가능
    }
}