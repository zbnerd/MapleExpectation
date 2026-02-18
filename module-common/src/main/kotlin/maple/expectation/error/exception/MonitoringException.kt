package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class MonitoringException : ServerBaseException(CommonErrorCode.INTERNAL_SERVER_ERROR)
