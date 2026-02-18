package maple.expectation.error.exception.base

import maple.expectation.error.ErrorCode
import maple.expectation.error.exception.marker.CircuitBreakerRecordMarker

/**
 * Base class for server-side (5xx) system exceptions.
 *
 * <p>Represents infrastructure and system errors (database failures, external API
 * errors, resource exhaustion, etc.). These exceptions:
 * <ul>
 *   <li>Implement CircuitBreakerRecordMarker to trigger circuit breaker</li>
 *   <li>Return 5xx HTTP status codes</li>
 *   <li>Log detailed error information for debugging</li>
 *   <li>Include cause chain for failure analysis</li>
 * </ul>
 *
 * @see ErrorCode
 * @see CircuitBreakerRecordMarker
 */
abstract class ServerBaseException : BaseException, CircuitBreakerRecordMarker {

    /**
     * Create exception with static error message.
     *
     * @param errorCode Error code enum (typically 5xx status)
     */
    constructor(errorCode: ErrorCode) : super(errorCode)

    /**
     * Create exception with dynamically formatted message.
     *
     * <p>Use for server errors that need contextual information (file names,
     * request IDs, timestamps, etc.).
     *
     * @param errorCode Error code enum with format string
     * @param args Arguments to format into message
     */
    constructor(errorCode: ErrorCode, vararg args: Any?) : super(errorCode, *args)

    /**
     * Create exception with static message and root cause.
     *
     * @param errorCode Error code enum
     * @param cause Root cause exception
     */
    constructor(errorCode: ErrorCode, cause: Throwable) : super(errorCode, cause)

    /**
     * Create exception with dynamic message and root cause.
     *
     * <p>Most comprehensive constructor - includes both context and cause.
     *
     * @param errorCode Error code enum
     * @param cause Root cause exception
     * @param args Arguments to format into message
     */
    constructor(errorCode: ErrorCode, cause: Throwable, vararg args: Any?) : super(
        errorCode,
        cause,
        *args
    )
}
