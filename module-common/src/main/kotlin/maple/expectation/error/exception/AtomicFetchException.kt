@file:JvmName("AtomicFetchException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

open class AtomicFetchException(message: String, cause: Throwable) : ServerBaseException(
    CommonErrorCode.DATA_PROCESSING_ERROR,
    cause,
    message
) {
}
