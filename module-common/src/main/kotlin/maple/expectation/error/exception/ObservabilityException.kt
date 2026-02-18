package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * 관측 가능성(Observability) 기능 실패 예외 (5xx Server Error)
 *
 * 메트릭 수집, 로깅 등 모니터링 기능 실패 시 발생합니다.
 *
 * @property message 실패 메시지
 * @property cause 원인 예외
 */
class ObservabilityException(
    message: String,
    cause: Throwable? = null
) : ServerBaseException(CommonErrorCode.INTERNAL_SERVER_ERROR, message) {

    init {
        if (cause != null) {
            initCause(cause)
        }
    }
}
