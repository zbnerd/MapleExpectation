package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class UnsupportedCalculationEngineException(engine: String) : ServerBaseException(
    CommonErrorCode.INVALID_INPUT_VALUE,
    engine
)
