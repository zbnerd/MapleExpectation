package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

public class MapleDataProcessingException extends ServerBaseException implements CircuitBreakerIgnoreMarker {

    // 기존 생성자 (메시지만 받는 경우)
    public MapleDataProcessingException(String detail) {
        super(CommonErrorCode.DATA_PROCESSING_ERROR, detail);
    }

    /**
     * ✅ [추가] 메시지와 발생 원인(Throwable)을 함께 받는 생성자
     * ExceptionTranslator.forMaple()에서 빨간 줄을 지우기 위해 반드시 필요합니다.
     */
    public MapleDataProcessingException(String detail, Throwable cause) {
        super(CommonErrorCode.DATA_PROCESSING_ERROR, detail, cause);
    }
}