@file:JvmName("DlqNotFoundException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class DlqNotFoundException : ClientBaseException {

    /**
     * Constructor with event ID (Long)
     */
    constructor(eventId: Long) : super(
        CommonErrorCode.DLQ_NOT_FOUND,
        "DLQ event not found: $eventId"
    )

    /**
     * Constructor with event ID (String)
     */
    constructor(eventId: String) : super(
        CommonErrorCode.DLQ_NOT_FOUND,
        eventId
    )

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.DLQ_NOT_FOUND,
        cause,
        message
    )
}
