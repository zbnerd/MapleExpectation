package maple.expectation.error.exception;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ServerBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerRecordMarker;

public class DatabaseNamedLockException extends ServerBaseException
    implements CircuitBreakerRecordMarker {

  public DatabaseNamedLockException(String functionName, String lockKey, Long waitTime) {
    super(
        CommonErrorCode.DATABASE_NAMED_LOCK_FAILED,
        functionName,
        lockKey,
        String.valueOf(waitTime));
  }

  public DatabaseNamedLockException(
      String functionName, String lockKey, Long waitTime, Throwable cause) {
    super(
        CommonErrorCode.DATABASE_NAMED_LOCK_FAILED,
        cause,
        functionName,
        lockKey,
        String.valueOf(waitTime));
  }
}
