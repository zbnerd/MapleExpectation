package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 유효하지 않은 캐릭터 상태 예외 (Issue #120)
 *
 * <p>Rich Domain Model에서 캐릭터 상태 검증 실패 시 발생
 */
public class InvalidCharacterStateException extends ClientBaseException
    implements CircuitBreakerIgnoreMarker {
  public InvalidCharacterStateException(String message) {
    super(CommonErrorCode.INVALID_CHARACTER_STATE, message);
  }
}
