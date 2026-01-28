package maple.expectation.global.error.exception;

import maple.expectation.global.error.CommonErrorCode;
import maple.expectation.global.error.exception.base.ClientBaseException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InvalidCharacterStateException 테스트 (Issue #120, #194)
 */
class InvalidCharacterStateExceptionTest {

    @Test
    @DisplayName("ClientBaseException 상속 확인")
    void shouldExtendClientBaseException() {
        // given
        InvalidCharacterStateException exception = new InvalidCharacterStateException("test message");

        // then
        assertThat(exception).isInstanceOf(ClientBaseException.class);
    }

    @Test
    @DisplayName("CircuitBreakerIgnoreMarker 구현 확인")
    void shouldImplementCircuitBreakerIgnoreMarker() {
        // given
        InvalidCharacterStateException exception = new InvalidCharacterStateException("test message");

        // then
        assertThat(exception).isInstanceOf(CircuitBreakerIgnoreMarker.class);
    }

    @Test
    @DisplayName("에러 코드 C005 확인")
    void shouldHaveCorrectErrorCode() {
        // given
        InvalidCharacterStateException exception = new InvalidCharacterStateException("test message");

        // then
        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_CHARACTER_STATE);
        assertThat(exception.getErrorCode().getCode()).isEqualTo("C005");
    }

    @Test
    @DisplayName("동적 메시지 포함 확인")
    void shouldContainDynamicMessage() {
        // given
        InvalidCharacterStateException exception = new InvalidCharacterStateException("OCID is null");

        // then
        assertThat(exception.getMessage()).contains("OCID is null");
    }
}
