package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class InsufficientResourceException(resourceType: String) : ServerBaseException(
    CommonErrorCode.INSUFFICIENT_RESOURCE,
    resourceType
)
