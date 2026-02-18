@file:JvmName("InvalidApiKeyException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class InvalidApiKeyException : ClientBaseException {

    /**
     * Default constructor with static message
     */
    constructor() : super(CommonErrorCode.INVALID_API_KEY)

    /**
     * Constructor with custom message
     */
    constructor(message: String) : super(
        CommonErrorCode.INVALID_API_KEY,
        message
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.INVALID_API_KEY,
        cause,
        message
    )
}
