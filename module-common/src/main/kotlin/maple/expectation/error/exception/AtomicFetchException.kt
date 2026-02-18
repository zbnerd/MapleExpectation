package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class AtomicFetchException : ServerBaseException(CommonErrorCode.DATA_PROCESSING_ERROR)
