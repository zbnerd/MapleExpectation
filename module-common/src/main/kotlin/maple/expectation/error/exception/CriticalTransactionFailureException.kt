@file:JvmName("CriticalTransactionFailureException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class CriticalTransactionFailureException : ServerBaseException {

    /**
     * Constructor with message only
     */
    constructor(message: String) : super(
        CommonErrorCode.DATABASE_TRANSACTION_FAILURE,
        message
    )

    /**
     * Constructor with message and cause
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.DATABASE_TRANSACTION_FAILURE,
        cause,
        message
    )
}
