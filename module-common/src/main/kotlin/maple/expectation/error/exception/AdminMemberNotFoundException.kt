package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class AdminMemberNotFoundException : ClientBaseException {

    /**
     * Default constructor
     */
    constructor() : super(CommonErrorCode.ADMIN_MEMBER_NOT_FOUND)

    /**
     * Constructor with admin fingerprint
     */
    constructor(adminFingerprint: String) : super(
        CommonErrorCode.ADMIN_MEMBER_NOT_FOUND,
        adminFingerprint
    )
}
