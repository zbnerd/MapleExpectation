package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class DlqNotFoundException(eventId: String) : ClientBaseException(
    CommonErrorCode.DLQ_NOT_FOUND,
    eventId
)
