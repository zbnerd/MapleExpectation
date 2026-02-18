package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class CharacterNotOwnedException(characterOcid: String) : ClientBaseException(
    CommonErrorCode.CHARACTER_NOT_OWNED,
    characterOcid
)
