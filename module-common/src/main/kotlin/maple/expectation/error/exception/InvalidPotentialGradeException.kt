package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

/**
 * 유효하지 않은 잠재능력 등급 예외 (4xx Client Error)
 *
 * 큐브 사용 시 입력되는 잠재능력 등급이 유효하지 않을 때 발생합니다.
 *
 * @property invalidGrade 유효하지 않은 등급명 (예: "레어", "에픽", "유니크", "레전드리"가 아닌 값)
 */
class InvalidPotentialGradeException(
    invalidGrade: String
) : ClientBaseException(
    CommonErrorCode.INVALID_INPUT_VALUE,
    "잠재능력 등급: $invalidGrade"
)
