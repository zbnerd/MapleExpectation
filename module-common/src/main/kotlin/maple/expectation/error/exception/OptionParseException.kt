package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class OptionParseException : ClientBaseException {

    /**
     * Constructor with option name
     */
    constructor(option: String) : super(
        CommonErrorCode.INVALID_INPUT_VALUE,
        option
    )

    /**
     * Constructor with option name and cause
     */
    constructor(option: String, cause: Throwable) : super(
        CommonErrorCode.INVALID_INPUT_VALUE,
        cause,
        "Parse failed for option: $option"
    )
}
