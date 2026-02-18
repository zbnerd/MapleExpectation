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
}
