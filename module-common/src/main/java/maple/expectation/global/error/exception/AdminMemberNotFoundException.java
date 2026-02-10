package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * Admin의 Member 계정이 DB에 존재하지 않을 때 발생
 *
 * <p>Admin에게 커피(후원)를 보내려면 해당 Admin의 fingerprint로 Member 레코드가 등록되어 있어야 합니다.
 *
 * <p>보안: fingerprint는 에러 메시지에 포함하지 않음
 */
public class AdminMemberNotFoundException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {
  public AdminMemberNotFoundException() {
    super(CommonErrorCode.ADMIN_MEMBER_NOT_FOUND);
  }
}
