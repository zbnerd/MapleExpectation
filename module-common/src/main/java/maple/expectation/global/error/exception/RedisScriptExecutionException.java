package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerRecordMarker;

/**
 * Redis Lua Script 실행 실패 시 발생하는 서버 예외
 *
 * <p>Redis 서버 재시작, 네트워크 장애, 스크립트 문법 오류 등으로 Lua Script 실행이 실패할 때 발생합니다.
 *
 * <p>{@link CircuitBreakerRecordMarker}를 구현하여 연속 실패 시 서킷브레이커가 작동하도록 합니다.
 */
public class RedisScriptExecutionException extends ServerBaseException
    implements CircuitBreakerRecordMarker {

  /**
   * 스크립트 이름만으로 예외 생성
   *
   * @param scriptName 실패한 스크립트 이름
   */
  public RedisScriptExecutionException(String scriptName) {
    super(CommonErrorCode.REDIS_SCRIPT_EXECUTION_FAILED, scriptName);
  }

  /**
   * 스크립트 이름과 원인 예외로 예외 생성
   *
   * @param scriptName 실패한 스크립트 이름
   * @param cause 원인 예외
   */
  public RedisScriptExecutionException(String scriptName, Throwable cause) {
    super(CommonErrorCode.REDIS_SCRIPT_EXECUTION_FAILED, cause, scriptName);
  }
}
