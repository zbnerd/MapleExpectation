package maple.expectation.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.ServerBaseException;

public class CriticalTransactionFailureException extends ServerBaseException {
    public CriticalTransactionFailureException(String detail, Throwable cause) {
        super(CommonErrorCode.DATABASE_TRANSACTION_FAILURE, detail);
        // 필요 시 별도로 cause를 로깅하거나 상위로 전달 가능
    }
}