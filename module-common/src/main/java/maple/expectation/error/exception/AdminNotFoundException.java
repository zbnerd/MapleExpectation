package maple.expectation.error.exception;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ClientBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * Admin 권한이 없는 fingerprint로 Admin 전용 기능 접근 시 발생
 *
 * <p>보안: fingerprint는 에러 메시지에 포함하지 않음
 */
public class AdminNotFoundException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {
  public AdminNotFoundException() {
    super(CommonErrorCode.ADMIN_NOT_FOUND);
  }
}
