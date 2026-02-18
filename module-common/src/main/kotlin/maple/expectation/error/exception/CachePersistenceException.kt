@file:JvmName("CachePersistenceException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class CachePersistenceException(ocid: String, cause: Throwable?) : ServerBaseException(
    CommonErrorCode.DATA_PROCESSING_ERROR,
    cause ?: RuntimeException("Cache persistence failed"),
    "캐시 영속화 실패 (ocid: $ocid)"
)
