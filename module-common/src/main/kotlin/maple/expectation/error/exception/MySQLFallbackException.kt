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
}
