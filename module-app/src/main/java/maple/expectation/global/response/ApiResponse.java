package maple.expectation.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * API 공통 응답 포맷
 *
 * @param success 성공 여부
 * @param data 응답 데이터 (성공 시)
 * @param error 에러 정보 (실패 시)
 * @param <T> 응답 데이터 타입
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorInfo error) {
  /** 성공 응답 생성 */
  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(true, data, null);
  }

  /** 실패 응답 생성 */
  public static <T> ApiResponse<T> error(String code, String message) {
    return new ApiResponse<>(false, null, new ErrorInfo(code, message));
  }

  /** 에러 정보 */
  public record ErrorInfo(String code, String message) {}
}
