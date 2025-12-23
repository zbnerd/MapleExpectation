package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 분산 락(Distributed Lock) 획득 실패 시 발생하는 서버 예외
 */
public class DistributedLockException extends ServerBaseException implements CircuitBreakerIgnoreMarker {

    // 1. 기존 에러 코드 중 DATABASE_TRANSACTION_FAILURE(S002) 혹은
    //    DATA_PROCESSING_ERROR(S004)를 활용하는 것이 적절합니다.
    public DistributedLockException(String detail) {
        super(CommonErrorCode.DATABASE_TRANSACTION_FAILURE, detail);
    }

    public DistributedLockException(String detail, Throwable cause) {
        super(CommonErrorCode.DATABASE_TRANSACTION_FAILURE, detail);
    }
}