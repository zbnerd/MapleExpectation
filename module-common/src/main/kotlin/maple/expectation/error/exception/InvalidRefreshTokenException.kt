package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class InvalidRefreshTokenException : ClientBaseException(CommonErrorCode.INVALID_REFRESH_TOKEN)
