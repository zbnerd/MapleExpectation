@file:JvmName("InvalidCharacterStateException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

/**
 * 유효하지 않은 캐릭터 상태 예외 (4xx Client Error)
 *
 * 캐릭터의 상태가 요청된 작업을 수행하기에 적절하지 않을 때 발생합니다.
 *
 * @property message 실패 메시지
 */
open class InvalidCharacterStateException(message: String) : ClientBaseException(
    CommonErrorCode.INVALID_CHARACTER_STATE,
    message
) {

    /**
     * expected + actual로 예외 생성 (Java 호환성)
     */
    constructor(expected: String, actual: String) : this("$expected (actual: $actual)")

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}
