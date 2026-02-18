package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class TokenReusedException(tokenId: String) : ClientBaseException(
    CommonErrorCode.TOKEN_USED,
    tokenId
)
