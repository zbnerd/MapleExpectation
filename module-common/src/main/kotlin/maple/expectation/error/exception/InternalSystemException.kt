@file:JvmName("InternalSystemException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * 내부 시스템 오류 예외 (5xx Server Error)
 *
 * 시스템 내부에서 발생한 예기치 않은 오류입니다.
 *
 * @property message 실패 메시지 (상세 원인)
 * @property cause 원인 예외 (optional)
 */
class InternalSystemException : ServerBaseException {

    /**
     * message만 받는 생성자
     */
    constructor(message: String) : super(CommonErrorCode.INTERNAL_SERVER_ERROR, message)

    /**
     * message + cause 받는 생성자
     */
    constructor(message: String, cause: Throwable) : super(CommonErrorCode.INTERNAL_SERVER_ERROR, message) {
        initCause(cause)
    }
}
