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
}
