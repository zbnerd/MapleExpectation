@file:JvmName("CachePersistenceException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * 캐시 영속화 실패 예외 (5xx Server Error)
 *
 * 캐시 데이터를 영구 저장소에 저장하는 중 오류가 발생했습니다.
 *
 * @property ocid 캐릭터 OCID
 * @property cause 원인 예외 (optional)
 */
open class CachePersistenceException(ocid: String, cause: Throwable? = null) : ServerBaseException(
    CommonErrorCode.DATA_PROCESSING_ERROR,
    cause ?: RuntimeException("Cache persistence failed"),
    "캐시 영속화 실패 (ocid: $ocid)"
)
