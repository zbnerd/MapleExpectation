package maple.expectation.error.exception.base

import maple.expectation.error.ErrorCode

/**
 * Base exception class for all application exceptions.
 *
 * <p>Provides unified error handling with:
 * <ul>
 *   <li>ErrorCode enum for type-safe error classification</li>
 *   <li>Dynamic message formatting via String.format()</li>
 *   <li>Cause chaining for debugging</li>
 * </ul>
 *
 * @see ErrorCode
 * @see ClientBaseException
 * @see ServerBaseException
 */
abstract class BaseException : RuntimeException {
    val errorCode: ErrorCode

    /**
     * Create exception with static error message.
     *
     * @param errorCode Error code enum containing status code and message
     */
    constructor(errorCode: ErrorCode) : super(errorCode.message) {
        this.errorCode = errorCode
    }

    /**
     * Create exception with dynamically formatted message.
     *
     * <p>Uses String.format() to insert args into error message template.
     *
     * <h3>Example:</h3>
     * <pre>
     * // ErrorCode message: "Character not found (IGN: %s)"
     * throw CharacterNotFoundException("MapleStory123")
     * // Results in: "Character not found (IGN: MapleStory123)"
     * </pre>
     *
     * @param errorCode Error code enum with format string message
     * @param args Variable arguments to format into message
     */
    constructor(errorCode: ErrorCode, vararg args: Any?) : super(String.format(errorCode.message, *args)) {
        this.errorCode = errorCode
    }

    /**
     * Create exception with cause and dynamically formatted message.
     *
     * <p>Preserves full exception chain for debugging while adding context.
     *
     * @param errorCode Error code enum
     * @param cause Root cause exception (e.g., IOException, SQLException)
     * @param args Message formatting arguments
     */
    constructor(errorCode: ErrorCode, cause: Throwable?, vararg args: Any?) : super(
        String.format(errorCode.message, *args),
        cause
    ) {
        this.errorCode = errorCode
    }
}
