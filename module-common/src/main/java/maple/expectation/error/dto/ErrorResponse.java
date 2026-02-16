package maple.expectation.error.dto;

import java.time.LocalDateTime;
import maple.expectation.error.ErrorCode;
import maple.expectation.error.exception.base.BaseException;

public record ErrorResponse(int status, String code, String message, LocalDateTime timestamp) {

  public static ErrorResponseBuilder builder() {
    return new ErrorResponseBuilder();
  }

  public static class ErrorResponseBuilder {
    private Integer status;
    private String code;
    private String message;
    private LocalDateTime timestamp;

    public ErrorResponseBuilder status(int status) {
      this.status = status;
      return this;
    }

    public ErrorResponseBuilder code(String code) {
      this.code = code;
      return this;
    }

    public ErrorResponseBuilder message(String message) {
      this.message = message;
      return this;
    }

    public ErrorResponseBuilder timestamp(LocalDateTime timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public ErrorResponse build() {
      return new ErrorResponse(
          status != null ? status : 500,
          code != null ? code : "E000",
          message != null ? message : "Unknown error",
          timestamp != null ? timestamp : LocalDateTime.now());
    }
  }

  /**
   * Create ErrorResponse from BaseException (business exception with dynamic message)
   *
   * <p>e.getMessage()를 통해 동적으로 가공된 메시지(예: 어떤 유저가 없는지)를 전달합니다. [cite: CLAUDE.md Section 11]
   */
  public static ErrorResponse from(BaseException e) {
    return ErrorResponse.builder()
        .status(e.getErrorCode().getStatusCode())
        .code(e.getErrorCode().getCode())
        .message(e.getMessage())
        .timestamp(LocalDateTime.now())
        .build();
  }

  /**
   * Create ErrorResponse from ErrorCode (system exception with static message)
   *
   * <p>ErrorCode Enum에 정의된 기본 메시지를 사용하며, 상세한 에러 내용은 보안을 위해 숨깁니다.
   */
  public static ErrorResponse from(ErrorCode errorCode) {
    return ErrorResponse.builder()
        .status(errorCode.getStatusCode())
        .code(errorCode.getCode())
        .message(errorCode.getMessage())
        .timestamp(LocalDateTime.now())
        .build();
  }
}
