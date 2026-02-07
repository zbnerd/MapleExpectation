package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerRecordMarker;

/**
 * 트랜잭션 스냅샷 획득 실패 예외
 *
 * <p>TransactionTemplate이 null을 반환한 경우(트랜잭션 실행 자체 실패) 발생합니다.
 *
 * <h3>Circuit Breaker</h3>
 *
 * <p>트랜잭션 실패는 시스템 장애로 분류 (CircuitBreakerRecordMarker)
 */
public class TransactionSnapshotException extends ServerBaseException
    implements CircuitBreakerRecordMarker {

  public TransactionSnapshotException(String userIgn) {
    super(
        CommonErrorCode.INTERNAL_SERVER_ERROR,
        "TransactionTemplate returned null (IGN: " + userIgn + ")");
  }
}
