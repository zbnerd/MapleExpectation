package maple.expectation.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.ServerBaseException;

public class MapleDataProcessingException extends ServerBaseException {
    public MapleDataProcessingException(String detail) {
        super(CommonErrorCode.DATA_PROCESSING_ERROR, detail);
    }
}