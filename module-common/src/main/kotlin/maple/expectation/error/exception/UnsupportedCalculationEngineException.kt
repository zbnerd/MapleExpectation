@file:JvmName("UnsupportedCalculationEngineException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class UnsupportedCalculationEngineException : ServerBaseException {

    /**
     * Default constructor
     */
    constructor() : super(
        CommonErrorCode.INVALID_INPUT_VALUE,
        "Unsupported calculation engine"
    )

    /**
     * Constructor with engine name
     */
    constructor(engine: String) : super(
        CommonErrorCode.INVALID_INPUT_VALUE,
        engine
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.INVALID_INPUT_VALUE,
        cause,
        message
    )
}
