package maple.expectation.error.exception.base;

import maple.expectation.error.ErrorCode;

/**
 * ClientBaseException: 사용자의 의도와 다를 때 발생하는 '비즈니스 예외' 4xx 계열의 에러를 처리하며, 유저에게 구체적인 실패 원인을 전달하는 것이
 * 목적입니다.
 */
public abstract class ClientBaseException extends BaseException {

  // 기본 생성자: 고정된 에러 메시지를 사용할 때
  public ClientBaseException(ErrorCode errorCode) {
    super(errorCode);
  }

  // 💡 추가: 동적 인자를 받는 생성자
  // 이를 통해 "존재하지 않는 캐릭터입니다 (IGN: %s)"와 같은 메시지 완성이 가능해집니다.
  public ClientBaseException(ErrorCode errorCode, Object... args) {
    super(errorCode, args);
  }
}
