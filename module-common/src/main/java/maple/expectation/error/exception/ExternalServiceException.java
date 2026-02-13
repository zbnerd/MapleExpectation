package maple.expectation.error.exception;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ServerBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerRecordMarker;

public class ExternalServiceException extends ServerBaseException
    implements CircuitBreakerRecordMarker {

  // 대상 서비스 이름(예: 넥슨 API)을 인자로 받아 메시지를 구성합니다.
  public ExternalServiceException(String serviceName) {
    super(CommonErrorCode.EXTERNAL_API_ERROR, serviceName);
  }

  // cause를 포함하여 예외 체이닝 지원
  public ExternalServiceException(String serviceName, Throwable cause) {
    super(CommonErrorCode.EXTERNAL_API_ERROR, cause, serviceName);
  }
}
