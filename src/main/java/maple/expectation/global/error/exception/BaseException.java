package maple.expectation.global.error.exception;

import lombok.Getter;
import maple.expectation.global.error.ErrorCode;

@Getter
public abstract class BaseException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String message; // 💡 필드 추가

    // 기본 생성자
    public BaseException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.message = errorCode.getMessage();
    }

    // 💡 동적 인자를 받는 생성자 (String.format 활용)
    public BaseException(ErrorCode errorCode, Object... args) {
        super(String.format(errorCode.getMessage(), args));
        this.errorCode = errorCode;
        this.message = String.format(errorCode.getMessage(), args);
    }
}