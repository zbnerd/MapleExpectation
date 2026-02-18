package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class SelfLikeNotAllowedException : ClientBaseException(CommonErrorCode.SELF_LIKE_NOT_ALLOWED)
