@file:JvmName("DuplicateLikeException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class DuplicateLikeException(characterOcid: String) : ClientBaseException(
    CommonErrorCode.DUPLICATE_LIKE,
    characterOcid
)
