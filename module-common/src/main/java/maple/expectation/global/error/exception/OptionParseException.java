package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 옵션 파싱 실패 예외
 *
 * <p>다음 경우에 발생:
 *
 * <ul>
 *   <li>옵션 문자열 형식 불일치
 *   <li>미지원 스탯 타입
 *   <li>숫자 추출 실패
 * </ul>
 */
public class OptionParseException extends ServerBaseException
    implements CircuitBreakerIgnoreMarker {

  public OptionParseException(String detail) {
    super(CommonErrorCode.DATA_PROCESSING_ERROR, "옵션 파싱 실패: " + detail);
  }

  public OptionParseException(String detail, Throwable cause) {
    super(CommonErrorCode.DATA_PROCESSING_ERROR, cause, "옵션 파싱 실패: " + detail);
  }
}
