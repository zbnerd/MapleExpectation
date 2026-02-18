package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class CompensationSyncException : ServerBaseException(CommonErrorCode.COMPENSATION_SYNC_FAILED)
