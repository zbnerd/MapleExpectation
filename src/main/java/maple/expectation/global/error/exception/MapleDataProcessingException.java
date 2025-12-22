package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;

public class MapleDataProcessingException extends ServerBaseException implements CircuitBreakerIgnoreMarker {
    public MapleDataProcessingException(String detail) {
        super(CommonErrorCode.DATA_PROCESSING_ERROR, detail);
    }
}