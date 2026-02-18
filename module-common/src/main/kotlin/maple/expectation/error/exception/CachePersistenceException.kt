@file:JvmName("CachePersistenceException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

open class CachePersistenceException : ServerBaseException {

    constructor(ocid: String, cause: Throwable?) : super(
        CommonErrorCode.DATA_PROCESSING_ERROR,
        cause ?: RuntimeException("Cache persistence failed"),
        "캐시 영속화 실패 (ocid: $ocid)"
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.DATA_PROCESSING_ERROR,
        cause,
        message
    )
}
