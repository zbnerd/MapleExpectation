package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class MonitoringException : ServerBaseException {

    /**
     * Default constructor with static message
     */
    constructor() : super(CommonErrorCode.INTERNAL_SERVER_ERROR)

    /**
     * Constructor with error code and capacity (for monitoring alerts)
     */
    constructor(errorCode: CommonErrorCode, capacity: Long) : super(
        errorCode,
        "System capacity exceeded. Pending: $capacity"
    )

    /**
     * Constructor with just error code
     */
    constructor(errorCode: CommonErrorCode) : super(errorCode)
}
