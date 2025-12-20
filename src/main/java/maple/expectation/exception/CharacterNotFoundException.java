package maple.expectation.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.ClientBaseException;

public class CharacterNotFoundException extends ClientBaseException {
    public CharacterNotFoundException(String userIgn) {
        super(CommonErrorCode.CHARACTER_NOT_FOUND, userIgn);
    }
}