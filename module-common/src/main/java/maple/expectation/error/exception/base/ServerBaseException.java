package maple.expectation.error.exception.base;

import maple.expectation.error.ErrorCode;

/**
 * ServerBaseException: 시스템 내부 오류나 나의 오타로 인해 발생하는 '서버 예외' 5xx 계열의 에러를 처리하며, 장애 회고를 위한 상세 로그를 남기는 것이
 * 주 목적입니다.
 */
public abstract class ServerBaseException extends BaseException {

  public ServerBaseException(ErrorCode errorCode) {
    super(errorCode);
  }

  // 서버 측 에러도 구체적인 ID나 파일명 등을 로그에 남기기 위해 추가합니다.
  public ServerBaseException(ErrorCode errorCode, Object... args) {
    super(errorCode, args);
  }

  // 🚀 [추가] 실제 에러(cause)를 포함하여 디버깅 정보 확보
  public ServerBaseException(ErrorCode errorCode, Throwable cause) {
    super(errorCode, cause);
  }

  // 🚀 [추가] 상세 메시지(args)와 실제 에러(cause)를 동시에 기록
  public ServerBaseException(ErrorCode errorCode, Throwable cause, Object... args) {
    super(errorCode, cause, args);
  }
}
