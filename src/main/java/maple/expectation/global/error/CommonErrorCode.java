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

    // === Auth Errors (4xx) ===
    INVALID_API_KEY("A001", "유효하지 않은 API Key입니다.", HttpStatus.UNAUTHORIZED),
    CHARACTER_NOT_OWNED("A002", "해당 캐릭터는 이 API Key 소유자의 캐릭터가 아닙니다 (IGN: %s)", HttpStatus.FORBIDDEN),
    SELF_LIKE_NOT_ALLOWED("A003", "자신의 캐릭터에는 좋아요를 누를 수 없습니다.", HttpStatus.FORBIDDEN),
    DUPLICATE_LIKE("A004", "이미 좋아요를 누른 캐릭터입니다.", HttpStatus.CONFLICT),
    UNAUTHORIZED("A005", "인증이 필요합니다.", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("A006", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN),

    // === Server Errors (5xx) ===
    INTERNAL_SERVER_ERROR("S001", "서버 내부 오류가 발생했습니다. (%s)", HttpStatus.INTERNAL_SERVER_ERROR),
    DATABASE_TRANSACTION_FAILURE("S002", "치명적인 트랜잭션 오류가 발생했습니다: %s", HttpStatus.INTERNAL_SERVER_ERROR),
    DATA_INITIALIZATION_FAILED("S003", "데이터 초기화 실패 (대상: %s)", HttpStatus.INTERNAL_SERVER_ERROR),
    DATA_PROCESSING_ERROR("S004", "데이터 처리 중 오류 발생 (%s)", HttpStatus.INTERNAL_SERVER_ERROR),
    EXTERNAL_API_ERROR("S005", "외부 API 호출 실패 (%s)", HttpStatus.SERVICE_UNAVAILABLE),
    SYSTEM_CAPACITY_EXCEEDED("S006", "시스템 부하가 임계치를 초과했습니다. (현재 대기량: %s)", HttpStatus.SERVICE_UNAVAILABLE),
    SERVICE_UNAVAILABLE("S007", "서비스가 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    REDIS_SCRIPT_EXECUTION_FAILED("S008", "Redis 스크립트 실행 실패 (스크립트: %s)", HttpStatus.INTERNAL_SERVER_ERROR),
    DATABASE_NAMED_LOCK_FAILED("S009", "DB named lock 처리 실패: %s (lockKey=%s, waitTime=%s)", HttpStatus.INTERNAL_SERVER_ERROR),
    API_TIMEOUT("S010", "외부 API 호출 시간 초과 (%s)", HttpStatus.SERVICE_UNAVAILABLE);


    private final String code;
    private final String message;
    private final HttpStatus status;
}