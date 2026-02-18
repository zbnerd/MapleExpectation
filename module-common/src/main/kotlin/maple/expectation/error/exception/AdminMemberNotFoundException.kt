@file:JvmName("AdminMemberNotFoundException")
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

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.ADMIN_MEMBER_NOT_FOUND,
        cause,
        message
    )
}
