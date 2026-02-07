package maple.expectation.global.error.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeoutException;
import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ServerBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerRecordMarker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * ApiTimeoutException 단위 테스트 (Issue #169, #173)
 *
 * <h4>5-Agent Council Round 2 결정</h4>
 *
 * <p>ApiTimeoutException은 CircuitBreakerRecordMarker를 구현하여 서킷브레이커가 타임아웃을 기록하도록 합니다.
 *
 * <ul>
 *   <li>ApiTimeoutException: API 레벨 timeout → 서킷브레이커 기록
 *   <li>ExpectationCalculationUnavailableException: Follower timeout → 서킷브레이커 무시
 * </ul>
 */
class ApiTimeoutExceptionTest {

  @Nested
  @DisplayName("예외 계층 구조 검증")
  class ExceptionHierarchyTests {

    @Test
    @DisplayName("ServerBaseException을 상속해야 함")
    void shouldExtendServerBaseException() {
      // Given
      ApiTimeoutException exception = new ApiTimeoutException("TestAPI");

      // Then
      assertThat(exception).isInstanceOf(ServerBaseException.class);
    }

    @Test
    @DisplayName("CircuitBreakerRecordMarker를 구현해야 함 (서킷브레이커 기록)")
    void shouldImplementCircuitBreakerRecordMarker() {
      // Given
      ApiTimeoutException exception = new ApiTimeoutException("TestAPI");

      // Then: 서킷브레이커가 이 예외를 기록해야 함
      assertThat(exception).isInstanceOf(CircuitBreakerRecordMarker.class);
    }
  }

  @Nested
  @DisplayName("에러 코드 검증")
  class ErrorCodeTests {

    @Test
    @DisplayName("CommonErrorCode.API_TIMEOUT 에러 코드 사용")
    void shouldUseApiTimeoutErrorCode() {
      // Given
      ApiTimeoutException exception = new ApiTimeoutException("NexonEquipmentAPI");

      // Then
      assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.API_TIMEOUT);
      assertThat(exception.getErrorCode().getCode()).isEqualTo("S010");
    }

    @Test
    @DisplayName("HTTP 상태 코드는 503 SERVICE_UNAVAILABLE")
    void shouldHaveServiceUnavailableStatus() {
      // Given
      ApiTimeoutException exception = new ApiTimeoutException("NexonEquipmentAPI");

      // Then
      assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("동적 메시지에 API 이름 포함")
    void shouldIncludeApiNameInMessage() {
      // Given
      String apiName = "NexonEquipmentAPI";
      ApiTimeoutException exception = new ApiTimeoutException(apiName);

      // Then: 메시지에 API 이름 포함
      assertThat(exception.getMessage()).contains(apiName);
    }
  }

  @Nested
  @DisplayName("원인 예외 체이닝 검증")
  class CauseChainTests {

    @Test
    @DisplayName("TimeoutException을 원인으로 포함 가능")
    void shouldPreserveCauseChain() {
      // Given
      TimeoutException cause = new TimeoutException("Connection timeout after 25 seconds");
      ApiTimeoutException exception = new ApiTimeoutException("NexonEquipmentAPI", cause);

      // Then: cause chain 보존
      assertThat(exception.getCause()).isEqualTo(cause);
      assertThat(exception.getCause().getMessage())
          .isEqualTo("Connection timeout after 25 seconds");
    }

    @Test
    @DisplayName("cause 없이 생성 가능")
    void shouldWorkWithoutCause() {
      // Given
      ApiTimeoutException exception = new ApiTimeoutException("NexonEquipmentAPI");

      // Then: cause가 null이어도 정상 동작
      assertThat(exception.getCause()).isNull();
      assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.API_TIMEOUT);
    }
  }
}
