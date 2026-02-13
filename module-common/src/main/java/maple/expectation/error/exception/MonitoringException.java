package maple.expectation.error.exception;

import maple.expectation.error.ErrorCode;
import maple.expectation.error.exception.base.ServerBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

/** MonitoringException: 시스템 모니터링 중 임계치 초과나 지표 이상 시 발생 */
public class MonitoringException extends ServerBaseException implements CircuitBreakerIgnoreMarker {

  public MonitoringException(ErrorCode errorCode) {
    super(errorCode);
  }

  public MonitoringException(ErrorCode errorCode, Object... args) {
    super(errorCode, args);
  }
}
