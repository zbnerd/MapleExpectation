@file:JvmName("InsufficientResourceException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

open class InsufficientResourceException(resourceType: String) : ServerBaseException(
    CommonErrorCode.INSUFFICIENT_RESOURCE,
    resourceType
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
