@file:JvmName("TokenReusedException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class TokenReusedException : ClientBaseException {

    /**
     * Default constructor
     */
    constructor() : super(CommonErrorCode.TOKEN_USED)

    /**
     * Constructor with token ID
     */
    constructor(tokenId: String) : super(
        CommonErrorCode.TOKEN_USED,
        tokenId
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.TOKEN_USED,
        cause,
        message
    )
}
