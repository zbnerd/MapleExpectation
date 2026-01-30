package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 좋아요 동기화 서킷 오픈 예외 (Issue #285: P1-3)
 *
 * <p>CircuitBreaker가 열려 batch 동기화가 실패할 때 발생합니다.
 * 상위 레이어에서 보상 트랜잭션을 실행하도록 전파합니다.</p>
 *
 * <p>CircuitBreakerIgnoreMarker: 서킷 오픈 자체는 서킷 카운트에 포함하지 않음</p>
 */
public class LikeSyncCircuitOpenException extends ServerBaseException implements CircuitBreakerIgnoreMarker {

    public LikeSyncCircuitOpenException(Throwable cause) {
        super(CommonErrorCode.LIKE_SYNC_CIRCUIT_OPEN, cause, cause.getMessage());
    }
}
