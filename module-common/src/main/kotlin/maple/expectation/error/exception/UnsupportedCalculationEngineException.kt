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
}
