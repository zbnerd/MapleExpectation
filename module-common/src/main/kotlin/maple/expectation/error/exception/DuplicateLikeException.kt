@file:JvmName("DuplicateLikeException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

open class DuplicateLikeException(characterOcid: String) : ClientBaseException(
    CommonErrorCode.DUPLICATE_LIKE,
    characterOcid
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
