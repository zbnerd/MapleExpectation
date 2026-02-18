package maple.expectation.infrastructure.ratelimit.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

/**
 * Rate Limit 초과 예외 (4xx Client Error)
 *
 * 요청 한도를 초과했습니다.
 */
class RateLimitExceededException : ClientBaseException {

    /**
     * retryAfterSeconds만 받는 생성자 (기본)
     */
    constructor(retryAfterSeconds: Int) : super(
        CommonErrorCode.RATE_LIMIT_EXCEEDED,
        retryAfterSeconds
    ) {
        this.retryAfterSeconds = retryAfterSeconds
    }

    /**
     * (userId, retryAfterSeconds, capacity, refillRate) 받는 생성자 (Java 호환성)
     */
    constructor(
        userId: String,
        retryAfterSeconds: Int,
        capacity: Int,
        refillRate: Int
    ) : super(
        CommonErrorCode.RATE_LIMIT_EXCEEDED,
        retryAfterSeconds
    ) {
        this.retryAfterSeconds = retryAfterSeconds
    }

    /**
     * 모든 파라미터 + cause 받는 생성자 (Java 호환성)
     */
    constructor(
        userId: String?,
        retryAfterSeconds: Int,
        capacity: Int?,
        refillRate: Int?,
        cause: Throwable?
    ) : super(
        CommonErrorCode.RATE_LIMIT_EXCEEDED,
        retryAfterSeconds
    ) {
        this.retryAfterSeconds = retryAfterSeconds
        if (cause != null) {
            initCause(cause)
        }
    }

    @get:JvmName("getRetryAfterSeconds")
    var retryAfterSeconds: Int = 0
}
