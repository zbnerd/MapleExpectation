package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class QueuePublishException(queueName: String) : ServerBaseException(
    CommonErrorCode.EVENT_CONSUMER_ERROR,
    queueName
)
