package maple.expectation.exception;

/**
 * 장비 데이터 처리(JSON 파싱, 압축, DB 변환) 중 발생하는 예외
 */
public class EquipmentDataProcessingException extends RuntimeException {

    public EquipmentDataProcessingException(String message) {
        super(message);
    }

    public EquipmentDataProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}