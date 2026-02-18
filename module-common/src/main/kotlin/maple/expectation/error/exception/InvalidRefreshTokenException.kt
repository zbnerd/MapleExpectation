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
}
