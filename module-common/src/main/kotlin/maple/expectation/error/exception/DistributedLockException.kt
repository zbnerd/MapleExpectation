package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ServerBaseException

class DistributedLockException : ServerBaseException {

    constructor(lockKey: String) : super(
        CommonErrorCode.DATABASE_TRANSACTION_FAILURE,
        "락 획득 실패: $lockKey"
    )

    constructor(lockKey: String, cause: Throwable) : super(
        CommonErrorCode.DATABASE_TRANSACTION_FAILURE,
        cause,
        "락 시도 중 오류: $lockKey"
    )
}
