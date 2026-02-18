@file:JvmName("ExpectationCalculationUnavailableException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

open class ExpectationCalculationUnavailableException : ServerBaseException {

    constructor() : super(CommonErrorCode.SERVICE_UNAVAILABLE)

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.SERVICE_UNAVAILABLE,
        cause,
        message
    )
}
