@file:JvmName("SessionNotFoundException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class SessionNotFoundException : ClientBaseException {

    /**
     * Default constructor (for method references)
     */
    constructor() : super(CommonErrorCode.SESSION_NOT_FOUND)

    /**
     * Constructor with session ID
     */
    constructor(sessionId: String) : super(
        CommonErrorCode.SESSION_NOT_FOUND,
        sessionId
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.SESSION_NOT_FOUND,
        cause,
        message
    )
}
