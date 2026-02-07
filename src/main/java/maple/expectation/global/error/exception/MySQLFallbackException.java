package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerRecordMarker;

/**
 * MySQL 장애 시 Fallback 실패 예외 (P0-3)
 *
 * <p>MySQL 장애 상황에서 Nexon API Fallback도 실패했을 때 발생합니다.
 *
 * <p>{@link CircuitBreakerRecordMarker}를 구현하여 서킷브레이커 실패율에 기록됩니다.
 *
 * <h4>발생 시나리오</h4>
 *
 * <ul>
 *   <li>MySQL DOWN + Redis 캐시 미스 + Nexon API 호출 실패
 *   <li>MySQL DOWN + Redis DOWN (Double Failure)
 * </ul>
 */
public class MySQLFallbackException extends ServerBaseException
    implements CircuitBreakerRecordMarker {

  public MySQLFallbackException(String ocid) {
    super(CommonErrorCode.MYSQL_FALLBACK_FAILED, ocid);
  }

  public MySQLFallbackException(String ocid, Throwable cause) {
    super(CommonErrorCode.MYSQL_FALLBACK_FAILED, cause, ocid);
  }
}
