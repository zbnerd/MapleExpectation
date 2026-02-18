package maple.expectation.error

/**
 * Common error codes used across the application
 */
enum class CommonErrorCode(
    override val code: String,
    override val message: String,
    override val statusCode: Int
) : ErrorCode {
    // === Client Errors (4xx) ===
    INVALID_INPUT_VALUE("C001", "잘못된 입력값입니다: %s", 400),
    CHARACTER_NOT_FOUND("C002", "존재하지 않는 캐릭터입니다 (IGN: %s)", 404),
    INSUFFICIENT_POINTS("C003", "포인트가 부족합니다 (보유: %s, 필요: %s)", 400),
    DEVELOPER_NOT_FOUND("C004", "해당 개발자를 찾을 수 없습니다 (ID: %s)", 404),
    INVALID_CHARACTER_STATE("C005", "유효하지 않은 캐릭터 상태입니다: %s", 400),

    // === Rate Limit Errors (4xx) - Issue #152 ===
    RATE_LIMIT_EXCEEDED("R001", "요청 한도를 초과했습니다. %s초 후 다시 시도해주세요.", 429),

    // === Auth Errors (4xx) ===
    INVALID_API_KEY("A001", "유효하지 않은 API Key입니다.", 401),
    CHARACTER_NOT_OWNED("A002", "해당 캐릭터는 이 API Key 소유자의 캐릭터가 아닙니다 (IGN: %s)", 403),
    SELF_LIKE_NOT_ALLOWED("A003", "자신의 캐릭터에는 좋아요를 누를 수 없습니다.", 403),
    DUPLICATE_LIKE("A004", "이미 좋아요를 누른 캐릭터입니다.", 409),
    UNAUTHORIZED("A005", "인증이 필요합니다.", 401),
    FORBIDDEN("A006", "접근 권한이 없습니다.", 403),
    ADMIN_NOT_FOUND("A007", "유효하지 않은 Admin입니다.", 404),
    ADMIN_MEMBER_NOT_FOUND("A008", "Admin의 Member 계정이 존재하지 않습니다.", 404),
    SENDER_MEMBER_NOT_FOUND("A009", "발신자 Member 계정이 존재하지 않습니다 (uuid: %s)", 404),
    INVALID_REFRESH_TOKEN("A010", "유효하지 않은 Refresh Token입니다.", 401),
    REFRESH_TOKEN_EXPIRED("A011", "Refresh Token이 만료되었습니다. 다시 로그인해주세요.", 401),
    TOKEN_USED("A012", "이미 사용된 토큰입니다. 보안을 위해 재로그인이 필요합니다.", 401),
    SESSION_NOT_FOUND("A013", "세션이 만료되었습니다. 다시 로그인해주세요.", 401),

    // === DLQ Errors (4xx) ===
    DLQ_NOT_FOUND("D001", "해당 DLQ 항목을 찾을 수 없습니다 (ID: %s)", 404),
    DLQ_ALREADY_PROCESSED("D002", "이미 재처리된 DLQ 항목입니다 (requestId: %s)", 409),

    // === Server Errors (5xx) ===
    INTERNAL_SERVER_ERROR("S001", "서버 내부 오류가 발생했습니다. (%s)", 500),
    DATABASE_TRANSACTION_FAILURE("S002", "치명적인 트랜잭션 오류가 발생했습니다. (%s)", 500),
    DATA_INITIALIZATION_FAILED("S003", "데이터 초기화 실패 (대상: %s)", 500),
    DATA_PROCESSING_ERROR("S004", "데이터 처리 중 오류 발생 (%s)", 500),
    EXTERNAL_API_ERROR("S005", "외부 API 호출 실패 (%s)", 503),
    COMPRESSION_ERROR("S998", "압축/압축 해제 오류가 발생했습니다.", 500),
    SYSTEM_CAPACITY_EXCEEDED("S006", "시스템 부하가 임계치를 초과했습니다. (현재 대기량: %s)", 503),
    SERVICE_UNAVAILABLE("S007", "서비스가 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요.", 503),
    REDIS_SCRIPT_EXECUTION_FAILED("S008", "Redis 스크립트 실행 실패 (스크립트: %s)", 500),
    DATABASE_NAMED_LOCK_FAILED("S009", "DB named lock 처리 실패: %s (lockKey=%s, waitTime=%s)", 500),
    API_TIMEOUT("S010", "외부 API 호출 시간 초과 (%s)", 503),
    INSUFFICIENT_RESOURCE("S011", "리소스가 부족합니다: %s", 503),

    // === MySQL Resilience Errors (5xx) - Issue #218 ===
    MYSQL_FALLBACK_FAILED("S012", "MySQL 장애 시 Fallback 실패 (ocid: %s)", 503),
    COMPENSATION_SYNC_FAILED("S013", "Compensation Log 동기화 실패 (entryId: %s)", 500),

    // === Like Sync Errors (5xx) - Issue #285 ===
    LIKE_SYNC_CIRCUIT_OPEN("S014", "좋아요 동기화 서킷이 열렸습니다 (%s)", 503),

    // === Expectation Calculation Errors (5xx) ===
    STARFORCE_TABLE_NOT_INITIALIZED("S015", "스타포스 테이블 초기화가 완료되지 않았습니다.", 503),
    CACHE_DATA_NOT_FOUND("S016", "캐시 데이터를 찾을 수 없습니다 (key: %s)", 500),

    // === Event Handler Errors ===
    EVENT_HANDLER_ERROR("E001", "이벤트 핸들러가 잘못되었습니다. (%s)", 500),
    EVENT_CONSUMER_ERROR("E002", "이벤트 컨슈머가 잘못되었습니다. (%s)", 500),
    COMMON_ERROR("U999", "알 수 없는 에러 코드입니다.", 500);

    /**
     * Format the error message with the provided arguments
     */
    fun formatMessage(vararg args: Any): String {
        return String.format(message, *args)
    }
}