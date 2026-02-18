package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class OptionParseException(option: String) : ClientBaseException(
    CommonErrorCode.INVALID_INPUT_VALUE,
    option
)
