@file:JvmName("CacheDataNotFoundException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

open class CacheDataNotFoundException : ServerBaseException {

    constructor(cacheKey: String) : super(
        CommonErrorCode.CACHE_DATA_NOT_FOUND,
        cacheKey
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.CACHE_DATA_NOT_FOUND,
        cause,
        message
    )
}
