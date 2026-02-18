@file:JvmName("ExternalApiException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.ErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class ExternalApiException : ServerBaseException {

    constructor(errorCode: ErrorCode) : super(errorCode)

    constructor(errorCode: ErrorCode, vararg args: Any?) : super(errorCode, *args)

    constructor(errorCode: ErrorCode, cause: Throwable) : super(errorCode, cause)

    constructor(errorCode: ErrorCode, cause: Throwable, vararg args: Any?) : super(
        errorCode,
        cause,
        *args
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.EXTERNAL_API_ERROR,
        cause,
        message
    )
}
