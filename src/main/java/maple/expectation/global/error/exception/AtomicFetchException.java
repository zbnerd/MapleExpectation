package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerRecordMarker;

/**
 * Redis Lua Script 원자적 fetch 실패 시 발생하는 서버 예외
 *
 * <p>금융수준 안전 설계:
 * <ul>
 *   <li>CircuitBreakerRecordMarker 구현으로 서킷브레이커 장애 기록</li>
 *   <li>Redis 장애 시 Graceful Degradation 트리거</li>
 *   <li>cause 보존으로 Exception Chaining 유지</li>
 * </ul>
 * </p>
 *
 * @since 2.0.0
 */
public class AtomicFetchException extends ServerBaseException implements CircuitBreakerRecordMarker {

    /**
     * Redis Script 실행 실패
     *
     * @param operation 작업 명 (예: "LuaScript:fetchAndMove")
     * @param key       대상 키
     * @param cause     원본 예외
     */
    public AtomicFetchException(String operation, String key, Throwable cause) {
        super(CommonErrorCode.DATA_PROCESSING_ERROR, cause,
                String.format("Redis 원자적 연산 실패 [%s] key=%s", operation, key));
    }

    /**
     * Redis Script 실행 실패 (cause 없음)
     *
     * @param operation 작업 명
     * @param key       대상 키
     */
    public AtomicFetchException(String operation, String key) {
        super(CommonErrorCode.DATA_PROCESSING_ERROR,
                String.format("Redis 원자적 연산 실패 [%s] key=%s", operation, key));
    }
}
