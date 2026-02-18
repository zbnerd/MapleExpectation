@file:JvmName("StarforceNotInitializedException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

open class StarforceNotInitializedException : ClientBaseException {
    constructor() : super(CommonErrorCode.STARFORCE_TABLE_NOT_INITIALIZED)

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : this() {
        initCause(cause)
    }
}
