package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class SessionNotFoundException(sessionId: String) : ClientBaseException(
    CommonErrorCode.SESSION_NOT_FOUND,
    sessionId
)
