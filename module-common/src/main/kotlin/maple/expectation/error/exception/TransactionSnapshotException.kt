@file:JvmName("TransactionSnapshotException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

open class TransactionSnapshotException(transactionId: String) : ServerBaseException(
    CommonErrorCode.DATABASE_TRANSACTION_FAILURE,
    transactionId
)