@file:JvmName("QueuePublishException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * Queue publish failure exception
 *
 * @property queueName Queue name or error message
 * @property cause Optional cause of the failure
 */
open class QueuePublishException @JvmOverloads constructor(
    queueName: String,
    cause: Throwable? = null
) : ServerBaseException(
    CommonErrorCode.EVENT_CONSUMER_ERROR,
    queueName
) {
    init {
        if (cause != null) {
            initCause(cause)
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
