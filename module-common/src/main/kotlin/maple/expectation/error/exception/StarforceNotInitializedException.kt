package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class StarforceNotInitializedException : ClientBaseException(CommonErrorCode.STARFORCE_TABLE_NOT_INITIALIZED)
