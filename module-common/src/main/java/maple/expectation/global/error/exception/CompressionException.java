package maple.expectation.global.error.exception;

import maple.expectation.global.error.ErrorCode;
import maple.expectation.global.error.ServerBaseException;

public class CompressionException extends ServerBaseException {

    public CompressionException(String message) {
        super(ErrorCode.COMPRESSION_ERROR, message);
    }

    public CompressionException(String message, Throwable cause) {
        super(ErrorCode.COMPRESSION_ERROR, message, cause);
    }
}
