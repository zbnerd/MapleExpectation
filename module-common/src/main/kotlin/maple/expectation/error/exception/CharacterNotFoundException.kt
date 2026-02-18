package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class CharacterNotFoundException(userIgn: String) : ClientBaseException(
    CommonErrorCode.CHARACTER_NOT_FOUND,
    userIgn
)
