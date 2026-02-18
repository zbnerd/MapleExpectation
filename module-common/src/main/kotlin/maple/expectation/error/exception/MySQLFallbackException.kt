package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class MySQLFallbackException : ServerBaseException(CommonErrorCode.MYSQL_FALLBACK_FAILED)
