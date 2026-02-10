package maple.expectation.aop.aspect;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.global.error.exception.InternalSystemException;
import maple.expectation.global.error.exception.base.ServerBaseException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Issue #166: NexonDataCacheAspect 예외 변환 로직 테스트
 *
 * <p>5-Agent Council 피드백 반영:
 *
 * <ul>
 *   <li>Red: TimeoutException -> ExternalServiceException (HTTP 503 보존)
 *   <li>Purple: 메시지에 메서드 컨텍스트 포함 검증
 *   <li>Yellow: null 입력, 중첩 예외, 이중 래핑 방지 테스트
 * </ul>
 */
class NexonDataCacheAspectExceptionTest {

  private RuntimeException invokeToRuntimeException(Throwable ex, String ocid) {
    NexonDataCacheAspect aspect = new NexonDataCacheAspect(null, null, null, null);
    return ReflectionTestUtils.invokeMethod(aspect, "toRuntimeException", ex, ocid);
  }

  // =======================================================================
  // 1. RuntimeException 계열 테스트
  // =======================================================================

  @Nested
  @DisplayName("Given RuntimeException input")
  class RuntimeExceptionInput {

    @Test
    @DisplayName("When RuntimeException, Then returns same instance")
    void shouldReturnSameRuntimeException() {
      RuntimeException input = new IllegalArgumentException("test");
      RuntimeException result = invokeToRuntimeException(input, "test-ocid");
      assertThat(result).isSameAs(input);
    }

    @Test
    @DisplayName(
        "When BaseException (CharacterNotFoundException), Then preserves type [Issue #166 Core]")
    void shouldPreserveBaseExceptionType() {
      CharacterNotFoundException input = new CharacterNotFoundException("testIgn");
      RuntimeException result = invokeToRuntimeException(input, "test-ocid");
      assertThat(result).isSameAs(input).isInstanceOf(CharacterNotFoundException.class);
    }

    @Test
    @DisplayName("When InternalSystemException, Then no double wrapping")
    void shouldNotDoubleWrap() {
      InternalSystemException input = new InternalSystemException("original:task");
      RuntimeException result = invokeToRuntimeException(input, "test-ocid");
      assertThat(result).isSameAs(input);
    }
  }

  // =======================================================================
  // 2. Error 테스트
  // =======================================================================

  @Nested
  @DisplayName("Given Error input")
  class ErrorInput {

    @Test
    @DisplayName("When OutOfMemoryError, Then throws immediately")
    void shouldThrowOOM() {
      Error input = new OutOfMemoryError("heap exhausted");
      assertThatThrownBy(() -> invokeToRuntimeException(input, "test-ocid")).isSameAs(input);
    }

    @Test
    @DisplayName("When StackOverflowError, Then throws immediately")
    void shouldThrowStackOverflow() {
      Error input = new StackOverflowError();
      assertThatThrownBy(() -> invokeToRuntimeException(input, "test-ocid"))
          .isInstanceOf(StackOverflowError.class);
    }
  }

  // =======================================================================
  // 3. TimeoutException 테스트 (Red Agent CRITICAL)
  // =======================================================================

  @Nested
  @DisplayName("Given TimeoutException input [Red Agent CRITICAL]")
  class TimeoutExceptionInput {

    @Test
    @DisplayName("When TimeoutException, Then wraps with ExternalServiceException (HTTP 503 보존)")
    void shouldWrapWithExternalServiceException() {
      TimeoutException input = new TimeoutException("API timeout");

      RuntimeException result = invokeToRuntimeException(input, "test-ocid");

      assertThat(result)
          .isInstanceOf(ExternalServiceException.class)
          .hasCause(input)
          .hasMessageContaining("timeout")
          .hasMessageContaining("test-ocid");
    }

    @Test
    @DisplayName("ExternalServiceException은 ServerBaseException 계층이다")
    void externalServiceExceptionIsServerBaseException() {
      TimeoutException input = new TimeoutException("timeout");

      RuntimeException result = invokeToRuntimeException(input, "ocid");

      assertThat(result).isInstanceOf(ServerBaseException.class);
    }
  }

  // =======================================================================
  // 4. InterruptedException 테스트
  // =======================================================================

  @Nested
  @DisplayName("Given InterruptedException input")
  class InterruptedExceptionInput {

    @AfterEach
    void cleanup() {
      Thread.interrupted(); // 인터럽트 플래그 클리어
    }

    @Test
    @DisplayName("When InterruptedException, Then restores interrupt flag")
    void shouldRestoreInterruptFlag() {
      InterruptedException input = new InterruptedException("interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isFalse();

      RuntimeException result = invokeToRuntimeException(input, "test-ocid");

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(result).isInstanceOf(InternalSystemException.class);
    }

    @Test
    @DisplayName("Message contains AsyncCallback context [Purple Agent]")
    void shouldContainAsyncCallbackContext() {
      InterruptedException input = new InterruptedException("interrupted");

      RuntimeException result = invokeToRuntimeException(input, "ocid123");

      assertThat(result)
          .hasMessageContaining("AsyncCallback")
          .hasMessageContaining("interrupted")
          .hasMessageContaining("ocid123");
    }
  }

  // =======================================================================
  // 5. Checked Exception 테스트
  // =======================================================================

  @Nested
  @DisplayName("Given Checked Exception input")
  class CheckedExceptionInput {

    @Test
    @DisplayName("When IOException, Then wraps with InternalSystemException")
    void shouldWrapIOException() {
      IOException input = new IOException("network error");

      RuntimeException result = invokeToRuntimeException(input, "test-ocid-123");

      assertThat(result)
          .isInstanceOf(InternalSystemException.class)
          .hasCause(input)
          .hasMessageContaining("AsyncCallback")
          .hasMessageContaining("test-ocid-123");
    }

    @Test
    @DisplayName("When nested exception, Then preserves cause chain")
    void shouldPreserveCauseChain() {
      IOException rootCause = new IOException("disk full");
      SQLException wrapped = new SQLException("query failed", rootCause);

      RuntimeException result = invokeToRuntimeException(wrapped, "ocid");

      assertThat(result).hasCause(wrapped).hasRootCause(rootCause);
    }
  }

  // =======================================================================
  // 6. Edge Cases (Yellow Agent 추가)
  // =======================================================================

  @Nested
  @DisplayName("Given edge cases")
  class EdgeCases {

    @Test
    @DisplayName("When ocid is null, Then message contains 'null' string")
    void shouldHandleNullOcid() {
      IOException input = new IOException("test");

      RuntimeException result = invokeToRuntimeException(input, null);

      assertThat(result).isInstanceOf(InternalSystemException.class).hasMessageContaining("null");
    }

    @Test
    @DisplayName("When ocid is empty, Then message is still valid")
    void shouldHandleEmptyOcid() {
      IOException input = new IOException("test");

      RuntimeException result = invokeToRuntimeException(input, "");

      assertThat(result).isInstanceOf(InternalSystemException.class);
    }
  }

  // =======================================================================
  // 7. Parameterized Tests
  // =======================================================================

  @ParameterizedTest(name = "[{index}] {0} -> {1}")
  @MethodSource("exceptionTypeProvider")
  @DisplayName("예외 타입별 변환 규칙 검증")
  void shouldTransformBasedOnExceptionType(
      String scenarioName,
      Throwable input,
      Class<? extends Throwable> expectedType,
      boolean shouldBeSameInstance) {

    RuntimeException result = invokeToRuntimeException(input, "param-ocid");

    assertThat(result).isInstanceOf(expectedType);
    if (shouldBeSameInstance) {
      assertThat(result).isSameAs(input);
    } else {
      assertThat(result).hasCause(input);
    }
  }

  static Stream<Arguments> exceptionTypeProvider() {
    return Stream.of(
        Arguments.of(
            "RuntimeException",
            new IllegalArgumentException("test"),
            IllegalArgumentException.class,
            true),
        Arguments.of(
            "BaseException",
            new CharacterNotFoundException("ign"),
            CharacterNotFoundException.class,
            true),
        Arguments.of(
            "InternalSystemException",
            new InternalSystemException("task"),
            InternalSystemException.class,
            true),
        Arguments.of(
            "TimeoutException",
            new TimeoutException("timeout"),
            ExternalServiceException.class,
            false),
        Arguments.of("IOException", new IOException("io"), InternalSystemException.class, false));
  }
}
