@file:JvmName("CompensationSyncException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

open class CompensationSyncException : ServerBaseException {

    constructor() : super(CommonErrorCode.COMPENSATION_SYNC_FAILED)

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.COMPENSATION_SYNC_FAILED,
        cause,
        message
    )
}
