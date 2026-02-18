package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class DeveloperNotFoundException(fingerprint: String) : ClientBaseException(
    CommonErrorCode.DEVELOPER_NOT_FOUND,
    fingerprint
)
