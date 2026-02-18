package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * 외부 서비스 장애 예외 (5xx Server Error)
 *
 * 외부 API 또는 서비스 호출 실패 시 발생합니다.
 *
 * @property message 실패 메시지 (상세 원인)
 * @property cause 원인 예외 (optional)
 */
class ExternalServiceException(
    message: String,
    cause: Throwable? = null
) : ServerBaseException(CommonErrorCode.EXTERNAL_API_ERROR, message) {

    init {
        if (cause != null) {
            initCause(cause)
        }
    }
}
