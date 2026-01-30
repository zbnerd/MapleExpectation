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
    INVALID_CHARACTER_STATE("C005", "유효하지 않은 캐릭터 상태입니다: %s", HttpStatus.BAD_REQUEST),

    // === Rate Limit Errors (4xx) - Issue #152 ===
    RATE_LIMIT_EXCEEDED("R001", "요청 한도를 초과했습니다. %s초 후 다시 시도해주세요.", HttpStatus.TOO_MANY_REQUESTS),

    // === Auth Errors (4xx) ===
    INVALID_API_KEY("A001", "유효하지 않은 API Key입니다.", HttpStatus.UNAUTHORIZED),
    CHARACTER_NOT_OWNED("A002", "해당 캐릭터는 이 API Key 소유자의 캐릭터가 아닙니다 (IGN: %s)", HttpStatus.FORBIDDEN),
    SELF_LIKE_NOT_ALLOWED("A003", "자신의 캐릭터에는 좋아요를 누를 수 없습니다.", HttpStatus.FORBIDDEN),
    DUPLICATE_LIKE("A004", "이미 좋아요를 누른 캐릭터입니다.", HttpStatus.CONFLICT),
    UNAUTHORIZED("A005", "인증이 필요합니다.", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("A006", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN),
    ADMIN_NOT_FOUND("A007", "유효하지 않은 Admin입니다.", HttpStatus.NOT_FOUND),
    ADMIN_MEMBER_NOT_FOUND("A008", "Admin의 Member 계정이 존재하지 않습니다.", HttpStatus.NOT_FOUND),
    SENDER_MEMBER_NOT_FOUND("A009", "발신자 Member 계정이 존재하지 않습니다 (uuid: %s)", HttpStatus.NOT_FOUND),
    INVALID_REFRESH_TOKEN("A010", "유효하지 않은 Refresh Token입니다.", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_EXPIRED("A011", "Refresh Token이 만료되었습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED),
    TOKEN_REUSED("A012", "이미 사용된 토큰입니다. 보안을 위해 재로그인이 필요합니다.", HttpStatus.UNAUTHORIZED),
    SESSION_NOT_FOUND("A013", "세션이 만료되었습니다. 다시 로그인해주세요.", HttpStatus.UNAUTHORIZED),

    // === DLQ Errors (4xx) ===
    DLQ_NOT_FOUND("D001", "해당 DLQ 항목을 찾을 수 없습니다 (ID: %s)", HttpStatus.NOT_FOUND),
    DLQ_ALREADY_REPROCESSED("D002", "이미 재처리된 DLQ 항목입니다 (requestId: %s)", HttpStatus.CONFLICT),

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
    API_TIMEOUT("S010", "외부 API 호출 시간 초과 (%s)", HttpStatus.SERVICE_UNAVAILABLE),
    INSUFFICIENT_RESOURCE("S011", "리소스가 부족합니다: %s", HttpStatus.SERVICE_UNAVAILABLE),

    // === MySQL Resilience Errors (5xx) - Issue #218 ===
    MYSQL_FALLBACK_FAILED("S012", "MySQL 장애 시 Fallback 실패 (ocid: %s)", HttpStatus.SERVICE_UNAVAILABLE),
    COMPENSATION_SYNC_FAILED("S013", "Compensation Log 동기화 실패 (entryId: %s)", HttpStatus.INTERNAL_SERVER_ERROR),

    // === Like Sync Errors (5xx) - Issue #285 ===
    LIKE_SYNC_CIRCUIT_OPEN("S014", "좋아요 동기화 서킷이 열렸습니다 (%s)", HttpStatus.SERVICE_UNAVAILABLE),

    // === Expectation Calculation Errors (5xx) ===
    STARFORCE_TABLE_NOT_INITIALIZED("S015", "스타포스 테이블 초기화가 완료되지 않았습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    CACHE_DATA_NOT_FOUND("S016", "캐시 데이터를 찾을 수 없습니다 (key: %s)", HttpStatus.INTERNAL_SERVER_ERROR);


    private final String code;
    private final String message;
    private final HttpStatus status;
}