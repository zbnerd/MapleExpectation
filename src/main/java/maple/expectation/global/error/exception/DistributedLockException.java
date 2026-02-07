package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/** 분산 락(Distributed Lock) 획득 실패 시 발생하는 서버 예외 */
public class DistributedLockException extends ServerBaseException
    implements CircuitBreakerIgnoreMarker {

  public DistributedLockException(String lockKey) {
    super(CommonErrorCode.DATABASE_TRANSACTION_FAILURE, "락 획득 실패: " + lockKey);
  }

  public DistributedLockException(String lockKey, Throwable cause) {
    super(CommonErrorCode.DATABASE_TRANSACTION_FAILURE, cause, "락 시도 중 오류: " + lockKey);
  }
}
