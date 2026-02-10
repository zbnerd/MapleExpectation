package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 발신자(Sender)의 Member 계정이 DB에 존재하지 않을 때 발생
 *
 * <p>포인트 이체 시 발신자 UUID로 Member를 조회할 수 없을 때 발생합니다.
 */
public class SenderMemberNotFoundException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {

  public SenderMemberNotFoundException(String maskedUuid) {
    super(CommonErrorCode.SENDER_MEMBER_NOT_FOUND, maskedUuid);
  }
}
