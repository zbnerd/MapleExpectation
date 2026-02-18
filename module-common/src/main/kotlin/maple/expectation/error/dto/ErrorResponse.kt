package maple.expectation.error.dto

import java.time.LocalDateTime
import maple.expectation.error.ErrorCode
import maple.expectation.error.exception.base.BaseException

/**
 * Standardized error response format for all API errors.
 *
 * <p>Provides consistent error structure across REST endpoints with:
 * <ul>
 *   <li>HTTP status code</li>
 *   <li>Error code identifier</li>
 *   <li>Human-readable message (dynamic for business exceptions)</li>
 *   <li>Timestamp for tracking</li>
 * </ul>
 *
 * @property status HTTP status code (4xx for client errors, 5xx for server errors)
 * @property code Error code identifier from ErrorCode enum
 * @property message Dynamic error message (includes context for business exceptions)
 * @property timestamp When the error occurred
 */
data class ErrorResponse(
    val status: Int,
    val code: String,
    val message: String,
    val timestamp: LocalDateTime = LocalDateTime.now()
) {

    companion object {

        /**
         * Create ErrorResponse from BaseException (business exception with dynamic message).
         *
         * <p>e.getMessage()를 통해 동적으로 가공된 메시지(예: 어떤 유저가 없는지)를 전달합니다.
         * [cite: CLAUDE.md Section 11]
         *
         * @param e Business exception with ErrorCode and dynamic message
         * @return ErrorResponse with exception details
         */
        @JvmStatic
        fun from(e: BaseException): ErrorResponse {
            return ErrorResponse(
                status = e.errorCode.statusCode,
                code = e.errorCode.code,
                message = e.message ?: "Unknown error",
                timestamp = LocalDateTime.now()
            )
        }

        /**
         * Create ErrorResponse from ErrorCode (system exception with static message).
         *
         * <p>ErrorCode Enum에 정의된 기본 메시지를 사용하며, 상세한 에러 내용은 보안을 위해 숨깁니다.
         *
         * @param errorCode Error code enum with static message
         * @return ErrorResponse with error code details
         */
        @JvmStatic
        fun from(errorCode: ErrorCode): ErrorResponse {
            return ErrorResponse(
                status = errorCode.statusCode,
                code = errorCode.code,
                message = errorCode.message,
                timestamp = LocalDateTime.now()
            )
        }

        /**
         * Create ErrorResponse with custom values.
         *
         * @param status HTTP status code
         * @param code Error code
         * @param message Error message
         * @return ErrorResponse with custom values
         */
        @JvmStatic
        fun from(status: Int, code: String, message: String): ErrorResponse {
            return ErrorResponse(
                status = status,
                code = code,
                message = message,
                timestamp = LocalDateTime.now()
            )
        }

        /**
         * Builder pattern for Java compatibility.
         */
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    /**
     * Builder class for Java compatibility.
     */
    class Builder {
        private var status: Int = 500
        private var code: String = "E000"
        private var message: String = "Unknown error"
        private var timestamp: LocalDateTime = LocalDateTime.now()

        fun status(status: Int) = apply { this.status = status }
        fun code(code: String) = apply { this.code = code }
        fun message(message: String) = apply { this.message = message }
        fun timestamp(timestamp: LocalDateTime) = apply { this.timestamp = timestamp }

        fun build(): ErrorResponse = ErrorResponse(
            status = status,
            code = code,
            message = message,
            timestamp = timestamp
        )
    }
}