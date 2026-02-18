package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class CriticalTransactionFailureException(transactionId: String) : ServerBaseException(
    CommonErrorCode.DATABASE_TRANSACTION_FAILURE,
    transactionId
)
