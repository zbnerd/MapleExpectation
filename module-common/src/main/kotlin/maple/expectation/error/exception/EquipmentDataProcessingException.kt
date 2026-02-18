@file:JvmName("EquipmentDataProcessingException")
package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * 장비 데이터 처리 예외 (5xx Server Error)
 *
 * 장비 데이터 파싱, 변환, 처리 중 오류가 발생했습니다.
 *
 * @property message 실패 메시지
 * @property cause 원인 예외
 */
class EquipmentDataProcessingException : ServerBaseException {

    /**
     * Constructor with message only
     */
    constructor(message: String) : super(
        CommonErrorCode.DATA_PROCESSING_ERROR,
        message
    )

    /**
     * Constructor with message and cause
     */
    constructor(message: String, cause: Throwable) : super(
        CommonErrorCode.DATA_PROCESSING_ERROR,
        cause,
        message
    )
}
