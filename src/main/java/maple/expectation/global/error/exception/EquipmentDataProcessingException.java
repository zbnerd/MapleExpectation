package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

/**
 * 장비 데이터 처리(JSON 파싱, 압축, DB 변환) 중 발생하는 예외
 *
 * <p>✅ P0: Cause 보존 - 원본 예외를 cause로 전달하여 스택 트레이스 유지
 */
public class EquipmentDataProcessingException extends ServerBaseException implements CircuitBreakerIgnoreMarker {
    public EquipmentDataProcessingException(String detail) {
        super(CommonErrorCode.DATA_PROCESSING_ERROR, detail);
    }

    /**
     * Cause를 보존하는 생성자
     *
     * @param detail 상세 메시지
     * @param cause 원인 예외
     */
    public EquipmentDataProcessingException(String detail, Throwable cause) {
        super(CommonErrorCode.DATA_PROCESSING_ERROR, detail, cause);
    }
}