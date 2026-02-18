package maple.expectation.error.exception

import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.base.ClientBaseException

class SenderMemberNotFoundException(senderId: String) : ClientBaseException(
    CommonErrorCode.SENDER_MEMBER_NOT_FOUND,
    senderId
)
