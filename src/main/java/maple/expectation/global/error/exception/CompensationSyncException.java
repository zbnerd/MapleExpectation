package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerRecordMarker;

/**
 * Compensation Log 동기화 실패 예외 (P1-4)
 *
 * <p>MySQL 복구 후 Compensation Log를 DB에 동기화하는 과정에서 실패했을 때 발생합니다.</p>
 * <p>{@link CircuitBreakerRecordMarker}를 구현하여 서킷브레이커 실패율에 기록됩니다.</p>
 *
 * <h4>재시도 정책</h4>
 * <ul>
 *   <li>3회 재시도 (exponential backoff: 1s, 2s, 4s)</li>
 *   <li>3회 실패 시 DLQ로 이동</li>
 * </ul>
 */
public class CompensationSyncException extends ServerBaseException implements CircuitBreakerRecordMarker {

    public CompensationSyncException(String entryId) {
        super(CommonErrorCode.COMPENSATION_SYNC_FAILED, entryId);
    }

    public CompensationSyncException(String entryId, Throwable cause) {
        super(CommonErrorCode.COMPENSATION_SYNC_FAILED, cause, entryId);
    }
}
