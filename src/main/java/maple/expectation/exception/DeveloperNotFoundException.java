package maple.expectation.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.ClientBaseException;

public class DeveloperNotFoundException extends ClientBaseException {
    public DeveloperNotFoundException(String developerId) {
        super(CommonErrorCode.DEVELOPER_NOT_FOUND, developerId);
    }
}