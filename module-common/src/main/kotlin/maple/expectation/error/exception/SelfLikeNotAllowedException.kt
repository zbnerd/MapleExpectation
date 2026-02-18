@file:JvmName("SelfLikeNotAllowedException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class SelfLikeNotAllowedException : ClientBaseException {

    /**
     * Default constructor
     */
    constructor() : super(CommonErrorCode.SELF_LIKE_NOT_ALLOWED)

    /**
     * Constructor with user IGN and OCID
     */
    constructor(userIgn: String, ocid: String) : super(
        CommonErrorCode.SELF_LIKE_NOT_ALLOWED,
        "Self-like not allowed for user: $userIgn (OCID: $ocid)"
    )
}
