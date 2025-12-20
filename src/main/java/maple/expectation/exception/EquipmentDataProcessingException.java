package maple.expectation.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.ServerBaseException;

/**
 * 장비 데이터 처리(JSON 파싱, 압축, DB 변환) 중 발생하는 예외
 */
public class EquipmentDataProcessingException extends ServerBaseException {
    public EquipmentDataProcessingException(String detail) {
        super(CommonErrorCode.DATA_PROCESSING_ERROR, detail);
    }
}