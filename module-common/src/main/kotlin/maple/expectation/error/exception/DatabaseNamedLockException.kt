@file:JvmName("DatabaseNamedLockException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * DB Named Lock 처리 실패 예외 (5xx Server Error)
 *
 * MySQL GET_LOCK/RELEASE_LOCK 실패 시 발생합니다.
 *
 * @property operation 실행한 작업 (예: "GET_LOCK", "RELEASE_LOCK", "RELEASE_LOCK(non-owner)")
 * @property lockKey 락 키
 * @property waitTime 대기 시간 (밀리초), RELEASE_LOCK에서는 null
 */
class DatabaseNamedLockException(
    operation: String,
    lockKey: String,
    waitTime: Long?
) : ServerBaseException(
    CommonErrorCode.DATABASE_NAMED_LOCK_FAILED,
    operation,
    lockKey,
    waitTime ?: "N/A"
)
