@file:JvmName("CharacterNotOwnedException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class CharacterNotOwnedException : ClientBaseException {

    /**
     * Constructor with user IGN or character OCID
     */
    constructor(identifier: String) : super(
        CommonErrorCode.CHARACTER_NOT_OWNED,
        identifier
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.CHARACTER_NOT_OWNED,
        cause,
        message
    )
}
