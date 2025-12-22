package maple.expectation.global.error;

import lombok.Getter;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    // === Client Errors (4xx) ===
    INVALID_INPUT_VALUE("C001", "잘못된 입력값입니다: %s", HttpStatus.BAD_REQUEST),
    CHARACTER_NOT_FOUND("C002", "존재하지 않는 캐릭터입니다 (IGN: %s)", HttpStatus.NOT_FOUND),
    INSUFFICIENT_POINTS("C003", "포인트가 부족합니다 (보유: %s, 필요: %s)", HttpStatus.BAD_REQUEST),
    DEVELOPER_NOT_FOUND("C004", "해당 개발자를 찾을 수 없습니다 (ID: %s)", HttpStatus.NOT_FOUND),

    // === Server Errors (5xx) ===
    INTERNAL_SERVER_ERROR("S001", "서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    DATABASE_TRANSACTION_FAILURE("S002", "치명적인 트랜잭션 오류가 발생했습니다: %s", HttpStatus.INTERNAL_SERVER_ERROR),
    DATA_INITIALIZATION_FAILED("S003", "데이터 초기화 실패 (대상: %s)", HttpStatus.INTERNAL_SERVER_ERROR),
    DATA_PROCESSING_ERROR("S004", "데이터 처리 중 오류 발생 (%s)", HttpStatus.INTERNAL_SERVER_ERROR),
    EXTERNAL_API_ERROR("S005", "외부 API 호출 실패 (%s)", HttpStatus.SERVICE_UNAVAILABLE);


    private final String code;
    private final String message;
    private final HttpStatus status;
}