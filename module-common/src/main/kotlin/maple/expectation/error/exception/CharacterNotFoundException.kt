@file:JvmName("CharacterNotFoundException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

open class CharacterNotFoundException(userIgn: String) : ClientBaseException(
    CommonErrorCode.CHARACTER_NOT_FOUND,
    userIgn
) {
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
