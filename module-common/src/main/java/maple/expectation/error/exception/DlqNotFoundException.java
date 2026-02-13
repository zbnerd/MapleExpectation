package maple.expectation.error.exception;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * DLQ 항목을 찾을 수 없을 때 발생하는 예외
 *
 * <p>CircuitBreakerIgnoreMarker: 클라이언트 오류로 서킷브레이커 카운트 제외
 */
public class DlqNotFoundException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  public DlqNotFoundException(Long dlqId) {
    super(CommonErrorCode.DLQ_NOT_FOUND, dlqId);
  }
}
