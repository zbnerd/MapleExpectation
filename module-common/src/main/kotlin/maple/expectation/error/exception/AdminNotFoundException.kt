package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class AdminNotFoundException : ClientBaseException(CommonErrorCode.ADMIN_NOT_FOUND)
