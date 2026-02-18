package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class AdminMemberNotFoundException(adminFingerprint: String) : ClientBaseException(
    CommonErrorCode.ADMIN_MEMBER_NOT_FOUND,
    adminFingerprint
)
