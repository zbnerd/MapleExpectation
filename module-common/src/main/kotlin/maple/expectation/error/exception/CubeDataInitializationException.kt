@file:JvmName("CubeDataInitializationException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * 큐브 데이터 초기화 실패 예외 (5xx Server Error)
 *
 * 큐브 확률 테이블 등 필수 데이터 초기화에 실패했습니다.
 *
 * @property message 실패 메시지 (optional)
 */
class CubeDataInitializationException(
    message: String? = null
) : ServerBaseException(
    CommonErrorCode.DATA_INITIALIZATION_FAILED,
    message ?: "큐브 데이터"
)
