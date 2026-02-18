@file:JvmName("AdminNotFoundException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

open class AdminNotFoundException : ClientBaseException {

    constructor(adminId: String) : super(
        CommonErrorCode.ADMIN_NOT_FOUND,
        adminId
    )

    constructor() : super(
        CommonErrorCode.ADMIN_NOT_FOUND
    )
}
