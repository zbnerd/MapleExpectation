@file:JvmName("InvalidRefreshTokenException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class InvalidRefreshTokenException : ClientBaseException {

    /**
     * Default constructor
     */
    constructor() : super(CommonErrorCode.INVALID_REFRESH_TOKEN)

    /**
     * Constructor with token
     */
    constructor(token: String) : super(
        CommonErrorCode.INVALID_REFRESH_TOKEN,
        token
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.INVALID_REFRESH_TOKEN,
        cause,
        message
    )
}
