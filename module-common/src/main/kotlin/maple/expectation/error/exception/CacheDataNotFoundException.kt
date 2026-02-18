@file:JvmName("CacheDataNotFoundException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class CacheDataNotFoundException(cacheKey: String) : ServerBaseException(
    CommonErrorCode.CACHE_DATA_NOT_FOUND,
    cacheKey
)
