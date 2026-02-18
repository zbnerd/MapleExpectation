@file:JvmName("EventProcessingException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.ErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * 이벤트 처리 예외 (5xx Server Error)
 *
 * 이벤트 핸들러 또는 컨슈머 실행 중 오류가 발생했습니다.
 *
 * @property message 실패 메시지
 */
class EventProcessingException(
    message: String
) : ServerBaseException(
    CommonErrorCode.EVENT_HANDLER_ERROR,
    message
) {

    /**
     * ErrorCode와 message로 예외 생성 (Java 호환성)
     */
    constructor(
        errorCode: ErrorCode,
        message: String
    ) : this(message)

    /**
     * ErrorCode, cause, details로 예외 생성 (Java 호환성)
     */
    constructor(
        errorCode: ErrorCode,
        cause: Throwable,
        vararg details: String
    ) : this(
        buildMessage(errorCode, cause, details)
    ) {
        initCause(cause)
    }

    private companion object {
        fun buildMessage(errorCode: ErrorCode, cause: Throwable, details: Array<out String>): String {
            val base = if (details.isNotEmpty()) {
                "${details.joinToString(": ")} - ${cause.message}"
            } else {
                cause.message ?: "이벤트 처리 실패"
            }
            return "[${errorCode.code}] $base"
        }
    }
}
