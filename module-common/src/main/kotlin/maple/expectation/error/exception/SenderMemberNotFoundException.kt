@file:JvmName("SenderMemberNotFoundException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

open class SenderMemberNotFoundException(senderId: String) : ClientBaseException(
    CommonErrorCode.SENDER_MEMBER_NOT_FOUND,
    senderId
) {
    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}
