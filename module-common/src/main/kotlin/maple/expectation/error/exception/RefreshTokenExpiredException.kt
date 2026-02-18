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
}
