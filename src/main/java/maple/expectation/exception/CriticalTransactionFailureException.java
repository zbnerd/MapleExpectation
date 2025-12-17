package maple.expectation.exception;

public class CriticalTransactionFailureException extends RuntimeException /* implements ServerBaseException (예정) */ {

    public CriticalTransactionFailureException(String message) {
        super(message);
    }

    public CriticalTransactionFailureException(String message, Throwable cause) {
        super(message, cause);
    }
    
    // Global Exception Handler가 인식할 수 있는 ErrorCode 등을 추가할 수 있습니다.
}