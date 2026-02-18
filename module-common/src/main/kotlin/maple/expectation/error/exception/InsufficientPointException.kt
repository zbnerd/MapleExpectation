package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

/**
 * 포인트 잔액 부족 예외 (4xx Client Error)
 *
 * 사용자의 보유 포인트가 요청한 작업을 수행하기에 부족할 때 발생합니다.
 *
 * @property currentPoint 현재 보유 포인트
 * @property requiredPoint 필요한 포인트
 */
class InsufficientPointException(
    currentPoint: Long,
    requiredPoint: Long
) : ClientBaseException(
    CommonErrorCode.INSUFFICIENT_POINTS,
    currentPoint,
    requiredPoint
)
