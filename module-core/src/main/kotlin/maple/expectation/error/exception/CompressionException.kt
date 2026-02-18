package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class CompressionException : ServerBaseException {

    constructor(detail: String) : super(
        CommonErrorCode.COMPRESSION_ERROR,
        "압축 에러: $detail"
    )

    constructor(detail: String, cause: Throwable) : super(
        CommonErrorCode.COMPRESSION_ERROR,
        cause,
        "압축 에러: $detail"
    )
}
