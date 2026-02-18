@file:JvmName("ApiTimeoutException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * API 호출 시간 초과 예외 (5xx Server Error)
 *
 * 외부 API 호출이 지정된 시간 내에 완료되지 않았습니다.
 *
 * @property url API URL 또는 식별자
 * @property cause 원인 예외 (optional)
 */
class ApiTimeoutException(
    url: String,
    cause: Throwable? = null
) : ServerBaseException(
    CommonErrorCode.API_TIMEOUT,
    url
) {

    init {
        if (cause != null) {
            initCause(cause)
        }
    }
}
