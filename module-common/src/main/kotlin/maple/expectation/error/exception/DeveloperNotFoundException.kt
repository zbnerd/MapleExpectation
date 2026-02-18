@file:JvmName("DeveloperNotFoundException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

open class DeveloperNotFoundException(fingerprint: String) : ClientBaseException(
    CommonErrorCode.DEVELOPER_NOT_FOUND,
    fingerprint
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
