@file:JvmName("RefreshTokenExpiredException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class RefreshTokenExpiredException : ClientBaseException {

    /**
     * Default constructor
     */
    constructor() : super(CommonErrorCode.REFRESH_TOKEN_EXPIRED)

    /**
     * Constructor with token
     */
    constructor(token: String) : super(
        CommonErrorCode.REFRESH_TOKEN_EXPIRED,
        token
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.REFRESH_TOKEN_EXPIRED,
        cause,
        message
    )
}
