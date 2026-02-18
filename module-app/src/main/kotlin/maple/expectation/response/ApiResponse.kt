package maple.expectation.response

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * API common response format
 *
 * @param success success flag
 * @param response data (on success)
 * @param error error information (on failure)
 * @param T response data type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ErrorInfo? = null
) {
    /** Error information record */
    data class ErrorInfo(
        val code: String,
        val message: String
    )

    companion object {
        /**
         * Create success response
         */
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(true, data, null)

        /**
         * Create error response
         */
        fun <T> error(code: String, message: String): ApiResponse<T> =
            ApiResponse(false, null, ErrorInfo(code, message))

        /**
         * Create error response from ErrorCode
         */
        fun <T> error(errorCode: maple.expectation.error.ErrorCode): ApiResponse<T> =
            error(errorCode.code, errorCode.message)

        /**
         * Create error response with formatted message
         */
        fun <T> error(errorCode: maple.expectation.error.CommonErrorCode, vararg args: Any): ApiResponse<T> =
            error(errorCode.code, errorCode.formatMessage(*args))
    }
}