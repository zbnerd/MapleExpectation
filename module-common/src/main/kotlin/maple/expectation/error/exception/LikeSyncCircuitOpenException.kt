@file:JvmName("LikeSyncCircuitOpenException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * 좋아요 동기화 서킷브레이커 열림 예외 (5xx Server Error)
 *
 * 좋아요 동기화 실패가 반복되어 서킷브레이커가 열렸습니다.
 *
 * @property cause 원인 예외 (optional)
 */
class LikeSyncCircuitOpenException(
    cause: Throwable? = null
) : ServerBaseException(
    CommonErrorCode.LIKE_SYNC_CIRCUIT_OPEN,
    "좋아요 동기화 서킷이 열렸습니다"
) {

    init {
        if (cause != null) {
            initCause(cause)
        }
    }
}
