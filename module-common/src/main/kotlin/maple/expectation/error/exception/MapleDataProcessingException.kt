package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

/**
 * 메이플스토리 데이터 처리 예외 (5xx Server Error)
 *
 * 메이플스토리 데이터 파싱, 변환, 처리 중 오류가 발생했습니다.
 *
 * @property message 실패 메시지
 * @property cause 원인 예외
 */
class MapleDataProcessingException(
    message: String,
    cause: Throwable? = null
) : ServerBaseException(
    CommonErrorCode.DATA_PROCESSING_ERROR,
    message
) {

    init {
        if (cause != null) {
            initCause(cause)
        }
    }
}
