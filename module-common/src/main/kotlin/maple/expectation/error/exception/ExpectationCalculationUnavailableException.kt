@file:JvmName("ExpectationCalculationUnavailableException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class ExpectationCalculationUnavailableException : ServerBaseException(CommonErrorCode.SERVICE_UNAVAILABLE)
