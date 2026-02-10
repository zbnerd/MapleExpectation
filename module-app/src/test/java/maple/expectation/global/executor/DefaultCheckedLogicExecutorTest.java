package maple.expectation.global.executor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.function.CheckedRunnable;
import maple.expectation.global.executor.function.CheckedSupplier;
import maple.expectation.global.executor.policy.ExecutionPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DefaultCheckedLogicExecutor 단위 테스트
 *
 * <p>플랜에 명시된 14개 테스트 케이스를 검증합니다:
 *
 * <ul>
 *   <li>executeUnchecked: 9개
 *   <li>executeWithFinallyUnchecked: 5개
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DefaultCheckedLogicExecutorTest {

  @Mock private ExecutionPipeline pipeline;

  private DefaultCheckedLogicExecutor executor;

  private static final TaskContext CONTEXT = TaskContext.of("Test", "Execute", "testId");

  @BeforeEach
  void setUp() {
    executor = new DefaultCheckedLogicExecutor(pipeline);
  }

  /** Pipeline passthrough 설정: task를 그대로 실행 */
  @SuppressWarnings("unchecked")
  private <T> void setupPipelinePassthrough() throws Throwable {
    when(pipeline.executeRaw(any(ThrowingSupplier.class), any(TaskContext.class)))
        .thenAnswer(
            invocation -> {
              var supplier = invocation.getArgument(0, ThrowingSupplier.class);
              return supplier.get();
            });
  }

  // ========================================
  // executeUnchecked 테스트 (9개)
  // ========================================

  @Nested
  @DisplayName("executeUnchecked")
  class ExecuteUncheckedTest {

    @Test
    @DisplayName("1. 성공 케이스: 정상 반환 확인")
    void success_returnsResult() throws Throwable {
      // given
      setupPipelinePassthrough();
      CheckedSupplier<String> task = () -> "success";
      Function<Exception, RuntimeException> mapper = e -> new RuntimeException(e);

      // when
      String result = executor.executeUnchecked(task, CONTEXT, mapper);

      // then
      assertThat(result).isEqualTo("success");
    }

    @Test
    @DisplayName("2. task가 RuntimeException → 그대로 throw, mapper 미호출")
    void taskThrowsRuntimeException_throwsAsIs_mapperNotCalled() throws Throwable {
      // given
      setupPipelinePassthrough();
      RuntimeException expected = new IllegalArgumentException("runtime error");
      CheckedSupplier<String> task =
          () -> {
            throw expected;
          };
      AtomicInteger mapperCallCount = new AtomicInteger(0);
      Function<Exception, RuntimeException> mapper =
          e -> {
            mapperCallCount.incrementAndGet();
            return new RuntimeException(e);
          };

      // when & then
      assertThatThrownBy(() -> executor.executeUnchecked(task, CONTEXT, mapper)).isSameAs(expected);
      assertThat(mapperCallCount.get()).isZero();
    }

    @Test
    @DisplayName("3. task가 Exception(checked) → mapper 호출 1회, mapper 결과 throw")
    void taskThrowsCheckedException_mapperCalledOnce_throwsMappedResult() throws Throwable {
      // given
      setupPipelinePassthrough();
      IOException checkedException = new IOException("io error");
      CheckedSupplier<String> task =
          () -> {
            throw checkedException;
          };
      AtomicInteger mapperCallCount = new AtomicInteger(0);
      RuntimeException mappedException = new IllegalStateException("mapped");
      Function<Exception, RuntimeException> mapper =
          e -> {
            mapperCallCount.incrementAndGet();
            return mappedException;
          };

      // when & then
      assertThatThrownBy(() -> executor.executeUnchecked(task, CONTEXT, mapper))
          .isSameAs(mappedException);
      assertThat(mapperCallCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("4. task가 Error → 즉시 throw, mapper 미호출")
    void taskThrowsError_throwsImmediately_mapperNotCalled() throws Throwable {
      // given
      setupPipelinePassthrough();
      OutOfMemoryError error = new OutOfMemoryError("oom");
      CheckedSupplier<String> task =
          () -> {
            throw error;
          };
      AtomicInteger mapperCallCount = new AtomicInteger(0);
      Function<Exception, RuntimeException> mapper =
          e -> {
            mapperCallCount.incrementAndGet();
            return new RuntimeException(e);
          };

      // when & then
      assertThatThrownBy(() -> executor.executeUnchecked(task, CONTEXT, mapper)).isSameAs(error);
      assertThat(mapperCallCount.get()).isZero();
    }

    @Test
    @DisplayName("5. task가 Throwable(비-Exception) → IllegalStateException")
    @SuppressWarnings("unchecked")
    void taskThrowsNonExceptionThrowable_throwsIllegalStateException() throws Throwable {
      // given: Pipeline이 Throwable (non-Exception)을 던지도록 설정
      Throwable customThrowable = new Throwable("custom throwable") {};
      when(pipeline.executeRaw(any(ThrowingSupplier.class), any(TaskContext.class)))
          .thenThrow(customThrowable);
      Function<Exception, RuntimeException> mapper = e -> new RuntimeException(e);

      // when & then
      assertThatThrownBy(() -> executor.executeUnchecked(() -> "test", CONTEXT, mapper))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("non-Exception Throwable")
          .hasCause(customThrowable);
    }

    @Test
    @DisplayName("6. mapper가 null 반환 → IllegalStateException")
    void mapperReturnsNull_throwsIllegalStateException() throws Throwable {
      // given
      setupPipelinePassthrough();
      CheckedSupplier<String> task =
          () -> {
            throw new IOException("io error");
          };
      Function<Exception, RuntimeException> mapper = e -> null;

      // when & then
      assertThatThrownBy(() -> executor.executeUnchecked(task, CONTEXT, mapper))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("mapper returned null");
    }

    @Test
    @DisplayName("7. mapper가 RuntimeException throw → 그대로 throw")
    void mapperThrowsRuntimeException_throwsAsIs() throws Throwable {
      // given
      setupPipelinePassthrough();
      CheckedSupplier<String> task =
          () -> {
            throw new IOException("io error");
          };
      RuntimeException mapperException = new IllegalArgumentException("mapper threw this");
      Function<Exception, RuntimeException> mapper =
          e -> {
            throw mapperException;
          };

      // when & then
      assertThatThrownBy(() -> executor.executeUnchecked(task, CONTEXT, mapper))
          .isSameAs(mapperException);
    }

    @Test
    @DisplayName("8. mapper가 Error throw → 그대로 throw")
    void mapperThrowsError_throwsAsIs() throws Throwable {
      // given
      setupPipelinePassthrough();
      CheckedSupplier<String> task =
          () -> {
            throw new IOException("io error");
          };
      StackOverflowError mapperError = new StackOverflowError("mapper error");
      Function<Exception, RuntimeException> mapper =
          e -> {
            throw mapperError;
          };

      // when & then
      assertThatThrownBy(() -> executor.executeUnchecked(task, CONTEXT, mapper))
          .isSameAs(mapperError);
    }

    @Test
    @DisplayName("9. mapper가 Throwable(비-Runtime) throw → IllegalStateException")
    void mapperThrowsNonRuntimeThrowable_throwsIllegalStateException() throws Throwable {
      // given
      setupPipelinePassthrough();
      CheckedSupplier<String> task =
          () -> {
            throw new IOException("io error");
          };
      // mapper가 checked exception을 던지는 경우 (계약 위반)
      Function<Exception, RuntimeException> mapper =
          e -> {
            // Function.apply는 checked 예외를 던질 수 없으므로 sneaky throw 사용
            sneakyThrow(new IOException("mapper threw checked exception"));
            return null; // unreachable
          };

      // when & then
      assertThatThrownBy(() -> executor.executeUnchecked(task, CONTEXT, mapper))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("mapper violated contract");
    }
  }

  // ========================================
  // executeWithFinallyUnchecked 테스트 (5개)
  // ========================================

  @Nested
  @DisplayName("executeWithFinallyUnchecked")
  class ExecuteWithFinallyUncheckedTest {

    @Test
    @DisplayName("10. task 성공 + finalizer 성공 → 정상 반환 + finalizer 1회 실행")
    void taskSuccess_finalizerSuccess_returnsResult_finalizerCalledOnce() throws Throwable {
      // given
      setupPipelinePassthrough();
      AtomicInteger finalizerCallCount = new AtomicInteger(0);
      CheckedSupplier<String> task = () -> "success";
      CheckedRunnable finalizer = finalizerCallCount::incrementAndGet;
      Function<Exception, RuntimeException> mapper = e -> new RuntimeException(e);

      // when
      String result = executor.executeWithFinallyUnchecked(task, finalizer, CONTEXT, mapper);

      // then
      assertThat(result).isEqualTo("success");
      assertThat(finalizerCallCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
        "11. task Exception + finalizer Exception → 매핑된 예외 throw, finalizer 예외가 suppressed")
    void taskException_finalizerException_throwsMapped_finalizerAsSuppressed() throws Throwable {
      // given
      setupPipelinePassthrough();
      IOException taskException = new IOException("task failed");
      RuntimeException finalizerException = new IllegalStateException("finalizer failed");
      RuntimeException mappedException = new IllegalArgumentException("mapped", taskException);

      CheckedSupplier<String> task =
          () -> {
            throw taskException;
          };
      CheckedRunnable finalizer =
          () -> {
            throw finalizerException;
          };
      Function<Exception, RuntimeException> mapper = e -> mappedException;

      // when & then
      assertThatThrownBy(
              () -> executor.executeWithFinallyUnchecked(task, finalizer, CONTEXT, mapper))
          .isSameAs(mappedException)
          .satisfies(
              thrown -> {
                Throwable[] suppressed = thrown.getSuppressed();
                assertThat(suppressed).hasSize(1);
                assertThat(suppressed[0]).isSameAs(finalizerException);
              });
    }

    @Test
    @DisplayName("12. task Exception + finalizer Error → Error가 최종 throw (Error 우선, Must-fix 2)")
    void taskException_finalizerError_throwsError() throws Throwable {
      // given
      setupPipelinePassthrough();
      IOException taskException = new IOException("task failed");
      OutOfMemoryError finalizerError = new OutOfMemoryError("finalizer OOM");

      CheckedSupplier<String> task =
          () -> {
            throw taskException;
          };
      CheckedRunnable finalizer =
          () -> {
            throw finalizerError;
          };
      Function<Exception, RuntimeException> mapper = e -> new RuntimeException(e);

      // when & then
      assertThatThrownBy(
              () -> executor.executeWithFinallyUnchecked(task, finalizer, CONTEXT, mapper))
          .isSameAs(finalizerError);
    }

    @Test
    @DisplayName("13. task 성공 + finalizer RuntimeException → finalizer RuntimeException이 primary")
    void taskSuccess_finalizerRuntimeException_throwsFinalizerException() throws Throwable {
      // given
      setupPipelinePassthrough();
      RuntimeException finalizerException = new IllegalStateException("finalizer failed");

      CheckedSupplier<String> task = () -> "success";
      CheckedRunnable finalizer =
          () -> {
            throw finalizerException;
          };
      Function<Exception, RuntimeException> mapper = e -> new RuntimeException(e);

      // when & then
      assertThatThrownBy(
              () -> executor.executeWithFinallyUnchecked(task, finalizer, CONTEXT, mapper))
          .isSameAs(finalizerException);
    }

    @Test
    @DisplayName("14. task Exception + finalizer 성공 → 매핑된 예외 throw, finalizer 1회 실행")
    void taskException_finalizerSuccess_throwsMapped_finalizerCalledOnce() throws Throwable {
      // given
      setupPipelinePassthrough();
      AtomicInteger finalizerCallCount = new AtomicInteger(0);
      IOException taskException = new IOException("task failed");
      RuntimeException mappedException = new IllegalArgumentException("mapped", taskException);

      CheckedSupplier<String> task =
          () -> {
            throw taskException;
          };
      CheckedRunnable finalizer = finalizerCallCount::incrementAndGet;
      Function<Exception, RuntimeException> mapper = e -> mappedException;

      // when & then
      assertThatThrownBy(
              () -> executor.executeWithFinallyUnchecked(task, finalizer, CONTEXT, mapper))
          .isSameAs(mappedException);
      assertThat(finalizerCallCount.get()).isEqualTo(1);
    }
  }

  // ========================================
  // Utility
  // ========================================

  /** Sneaky throw: checked 예외를 unchecked처럼 던지기 */
  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
    throw (E) t;
  }
}
