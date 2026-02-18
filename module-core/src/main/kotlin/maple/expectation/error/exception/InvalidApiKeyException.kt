package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class InvalidApiKeyException : ClientBaseException(CommonErrorCode.INVALID_API_KEY)
