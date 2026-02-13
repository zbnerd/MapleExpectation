package maple.expectation.error.exception;

import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.base.ServerBaseException;
import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker;

public class CompressionException extends ServerBaseException
    implements CircuitBreakerIgnoreMarker {
  public CompressionException(String detail) {
    super(CommonErrorCode.DATA_PROCESSING_ERROR, "압축 에러: " + detail);
  }

  public CompressionException(String detail, Throwable cause) {
    super(CommonErrorCode.DATA_PROCESSING_ERROR, "압축 에러: " + detail, cause);
  }
}
