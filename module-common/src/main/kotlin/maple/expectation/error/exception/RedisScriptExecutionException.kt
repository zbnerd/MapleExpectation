@file:JvmName("RedisScriptExecutionException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * Redis 스크립트 실행 실패 예외 (5xx Server Error)
 *
 * Lua 스크립트 실행 중 오류가 발생했습니다.
 *
 * @property scriptName 스크립트 이름
 * @property cause 원인 예외
 */
class RedisScriptExecutionException(
    scriptName: String,
    cause: Throwable? = null
) : ServerBaseException(
    CommonErrorCode.REDIS_SCRIPT_EXECUTION_FAILED,
    scriptName
) {

    init {
        if (cause != null) {
            initCause(cause)
        }
    }
}
