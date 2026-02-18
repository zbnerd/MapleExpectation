@file:JvmName("ApiResponse")

package maple.expectation.response

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * API 공통 응답 포맷
 *
 * @param T 응답 데이터 타입
 * @property success 성공 여부
 * @property data 응답 데이터 (성공 시)
 * @property error 에러 정보 (실패 시)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ErrorInfo? = null
) {
    /** 성공 응답 생성 */
    companion object {
        @JvmStatic
        fun <T> success(data: T): ApiResponse<T> {
            return ApiResponse(true, data, null)
        }

        /** 실패 응답 생성 */
        @JvmStatic
        fun <T> error(code: String, message: String): ApiResponse<T> {
            return ApiResponse(false, null, ErrorInfo(code, message))
        }
    }

    /** 에러 정보 */
    data class ErrorInfo(
        val code: String,
        val message: String
    )
}
