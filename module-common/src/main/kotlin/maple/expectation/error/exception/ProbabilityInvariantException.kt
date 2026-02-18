package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class ProbabilityInvariantException(message: String) : ServerBaseException(
    CommonErrorCode.DATA_PROCESSING_ERROR,
    message
)
