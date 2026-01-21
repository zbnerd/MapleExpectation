package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

public class CriticalTransactionFailureException extends ServerBaseException implements CircuitBreakerIgnoreMarker {

    /**
     * 치명적 트랜잭션 실패 예외
     *
     * <p>Purple Agent P0 Fix: Exception chaining 유지로 root cause 보존.
     * 프로덕션 장애 분석 시 원인 추적 가능.</p>
     *
     * @param detail 상세 메시지
     * @param cause  원인 예외 (반드시 전달되어야 함)
     */
    public CriticalTransactionFailureException(String detail, Throwable cause) {
        super(CommonErrorCode.DATABASE_TRANSACTION_FAILURE, cause, detail);
    }
}