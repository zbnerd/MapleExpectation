package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class RefreshTokenExpiredException : ClientBaseException(CommonErrorCode.REFRESH_TOKEN_EXPIRED)
