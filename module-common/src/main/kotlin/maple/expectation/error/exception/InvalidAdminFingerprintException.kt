@file:JvmName("InvalidAdminFingerprintException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

/**
 * Invalid admin fingerprint exception
 *
 * <p>Thrown when an admin's fingerprint doesn't match the expected value for security verification
 * purposes.
 */
class InvalidAdminFingerprintException : ClientBaseException {

    /**
     * Default constructor
     */
    constructor() : super(CommonErrorCode.FORBIDDEN)

    /**
     * Constructor with custom message
     */
    constructor(message: String) : super(CommonErrorCode.FORBIDDEN, message)

    /**
     * Create exception with custom message and cause.
     *
     * @param message Custom error message
     * @param cause Root cause exception
     */
    constructor(message: String, cause: Throwable) : super(CommonErrorCode.FORBIDDEN, cause, message)
}
