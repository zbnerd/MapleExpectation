package maple.expectation.global.error.dto;

import lombok.Builder;
import maple.expectation.global.error.ErrorCode;
import maple.expectation.global.error.exception.base.BaseException;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

public record ErrorResponse(int status, String code, String message, LocalDateTime timestamp) {

    @Builder
    public ErrorResponse {}

    /**
     * [방법 1] BaseException을 받는 경우 (비즈니스 예외)
     * e.getMessage()를 통해 동적으로 가공된 메시지(예: 어떤 유저가 없는지)를 전달합니다. [cite: 11]
     */
    public static ResponseEntity<ErrorResponse> toResponseEntity(BaseException e) {
        return ResponseEntity
                .status(e.getErrorCode().getStatus())
                .body(ErrorResponse.builder()
                        .status(e.getErrorCode().getStatus().value())
                        .code(e.getErrorCode().getCode())
                        .message(e.getMessage())
                        .timestamp(LocalDateTime.now())
                        .build());
    }

    /**
     * [방법 2] ErrorCode를 직접 받는 경우 (예상치 못한 서버 예외)
     * Enum에 정의된 기본 메시지를 사용하며, 상세한 에러 내용은 보안을 위해 숨깁니다. [cite: 44]
     */
    public static ResponseEntity<ErrorResponse> toResponseEntity(ErrorCode errorCode) {
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.builder()
                        .status(errorCode.getStatus().value())
                        .code(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .timestamp(LocalDateTime.now())
                        .build());
    }
}