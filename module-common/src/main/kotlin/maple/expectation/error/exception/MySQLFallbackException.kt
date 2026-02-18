@file:JvmName("MySQLFallbackException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class MySQLFallbackException : ServerBaseException {

    /**
     * Default constructor with static message
     */
    constructor() : super(CommonErrorCode.MYSQL_FALLBACK_FAILED)

    /**
     * Constructor with ocid and cause
     */
    constructor(ocid: String, cause: Exception) : super(
        CommonErrorCode.MYSQL_FALLBACK_FAILED,
        cause,
        "MySQL fallback failed for OCID: $ocid"
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.MYSQL_FALLBACK_FAILED,
        cause,
        message
    )
}
