@file:JvmName("TransactionSnapshotException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

open class TransactionSnapshotException : ServerBaseException {

    constructor(transactionId: String) : super(
        CommonErrorCode.DATABASE_TRANSACTION_FAILURE,
        transactionId
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.DATABASE_TRANSACTION_FAILURE,
        cause,
        message
    )
}