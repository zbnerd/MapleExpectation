package maple.expectation.global.executor.policy;

import maple.expectation.global.executor.TaskContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * FinallyPolicy 단위 테스트
 *
 * <p>Codex 리뷰 기반 PROPAGATE failureMode 검증:</p>
 * <ul>
 *   <li>cleanup 실패가 관측되어야 함 (숨겨지면 안 됨)</li>
 *   <li>task 성공 + cleanup 실패 → cleanup 예외가 외부로 전파</li>
 *   <li>task 실패 + cleanup 실패 → cleanup 예외는 suppressed로 보존</li>
 * </ul>
 */
class FinallyPolicyTest {

    private static final TaskContext CONTEXT = TaskContext.of("Test", "Finally", "testId");

    @Nested
    @DisplayName("생성자 및 failureMode 검증")
    class ConstructorAndFailureModeTest {

        @Test
        @DisplayName("기본 생성자는 PROPAGATE 모드여야 한다 (금융급 기본값)")
        void defaultConstructor_mustBe_propagateMode() {
            // given
            FinallyPolicy policy = new FinallyPolicy(() -> {});

            // when & then
            assertThat(policy.failureMode())
                    .as("기본 생성자는 PROPAGATE 모드여야 함 (cleanup 실패 관측 필수)")
                    .isEqualTo(FailureMode.PROPAGATE);
        }

        @Test
        @DisplayName("명시적 PROPAGATE 모드 지정 가능")
        void explicitPropagateMode() {
            // given
            FinallyPolicy policy = new FinallyPolicy(() -> {}, FailureMode.PROPAGATE);

            // when & then
            assertThat(policy.failureMode()).isEqualTo(FailureMode.PROPAGATE);
        }

        @Test
        @DisplayName("SWALLOW 모드 지정 가능 (전역 메트릭/로깅용)")
        void swallowModeForMetrics() {
            // given
            FinallyPolicy policy = new FinallyPolicy(() -> {}, FailureMode.SWALLOW);

            // when & then
            assertThat(policy.failureMode()).isEqualTo(FailureMode.SWALLOW);
        }

        @Test
        @DisplayName("null cleanupTask는 NullPointerException")
        void nullCleanupTask_throwsNPE() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new FinallyPolicy(null))
                    .withMessageContaining("cleanupTask");
        }

        @Test
        @DisplayName("null failureMode는 NullPointerException")
        void nullFailureMode_throwsNPE() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new FinallyPolicy(() -> {}, null))
                    .withMessageContaining("failureMode");
        }
    }

    @Nested
    @DisplayName("SWALLOW 모드 동작 검증")
    class SwallowModeTest {

        @Test
        @DisplayName("SWALLOW: task 성공 + cleanup 실패 → 정상 반환 (cleanup 예외 흡수)")
        void swallow_taskSuccess_cleanupFails_returnsNormally() throws Throwable {
            // given
            AtomicInteger cleanupCount = new AtomicInteger(0);
            RuntimeException cleanupException = new IllegalStateException("cleanup failed");
            FinallyPolicy finallyPolicy = new FinallyPolicy(() -> {
                cleanupCount.incrementAndGet();
                throw cleanupException;
            }, FailureMode.SWALLOW);
            ExecutionPipeline pipeline = new ExecutionPipeline(List.of(finallyPolicy));

            // when
            String result = pipeline.executeRaw(() -> "success", CONTEXT);

            // then
            assertThat(result).isEqualTo("success");
            assertThat(cleanupCount.get())
                    .as("cleanup이 실행되어야 함 (예외가 발생해도)")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("SWALLOW: task 실패 + cleanup 실패 → task 예외만 전파 (cleanup 예외 흡수)")
        void swallow_taskFails_cleanupFails_onlyTaskExceptionPropagated() {
            // given
            RuntimeException taskException = new IllegalArgumentException("task failed");
            RuntimeException cleanupException = new IllegalStateException("cleanup failed");
            FinallyPolicy finallyPolicy = new FinallyPolicy(() -> { throw cleanupException; }, FailureMode.SWALLOW);
            ExecutionPipeline pipeline = new ExecutionPipeline(List.of(finallyPolicy));

            // when & then
            assertThatThrownBy(() -> pipeline.executeRaw(() -> { throw taskException; }, CONTEXT))
                    .isSameAs(taskException)
                    .satisfies(thrown -> {
                        // SWALLOW 모드에서는 cleanup 예외가 suppressed로 추가되지 않음
                        Throwable[] suppressed = thrown.getSuppressed();
                        assertThat(suppressed)
                                .as("SWALLOW 모드에서는 cleanup 예외가 suppressed에 없어야 함")
                                .isEmpty();
                    });
        }

        @Test
        @DisplayName("SWALLOW: cleanup Error → Error는 반드시 throw (금융급 필수)")
        void swallow_cleanupError_mustPropagate() {
            // given: SWALLOW 모드여도 Error는 절대 삼켜지면 안 됨
            OutOfMemoryError cleanupError = new OutOfMemoryError("cleanup OOM");
            FinallyPolicy finallyPolicy = new FinallyPolicy(() -> { throw cleanupError; }, FailureMode.SWALLOW);
            ExecutionPipeline pipeline = new ExecutionPipeline(List.of(finallyPolicy));

            // when & then: task 성공해도 cleanup Error는 반드시 전파
            assertThatThrownBy(() -> pipeline.executeRaw(() -> "success", CONTEXT))
                    .isInstanceOf(OutOfMemoryError.class)
                    .isSameAs(cleanupError);
        }

        @Test
        @DisplayName("SWALLOW: task Exception + cleanup Error → Error가 최종 throw (Error 우선)")
        void swallow_taskException_cleanupError_errorWins() {
            // given: task는 Exception, cleanup은 Error
            RuntimeException taskException = new IllegalArgumentException("task failed");
            OutOfMemoryError cleanupError = new OutOfMemoryError("cleanup OOM");
            FinallyPolicy finallyPolicy = new FinallyPolicy(() -> { throw cleanupError; }, FailureMode.SWALLOW);
            ExecutionPipeline pipeline = new ExecutionPipeline(List.of(finallyPolicy));

            // when & then: Error는 Exception보다 우선 (치명 상태 우선 원칙)
            assertThatThrownBy(() -> pipeline.executeRaw(() -> { throw taskException; }, CONTEXT))
                    .isInstanceOf(OutOfMemoryError.class)
                    .isSameAs(cleanupError);
        }
    }

    @Nested
    @DisplayName("ExecutionPipeline 통합: PROPAGATE 동작 검증")
    class PipelinePropagateTest {

        @Test
        @DisplayName("task 성공 + cleanup RuntimeException → cleanup 예외가 primary로 throw")
        void taskSuccess_cleanupFails_cleanupExceptionIsPrimary() {
            // given
            RuntimeException cleanupException = new IllegalStateException("cleanup failed");
            FinallyPolicy finallyPolicy = new FinallyPolicy(() -> { throw cleanupException; });
            ExecutionPipeline pipeline = new ExecutionPipeline(List.of(finallyPolicy));

            // when & then
            assertThatThrownBy(() -> pipeline.executeRaw(() -> "success", CONTEXT))
                    .isSameAs(cleanupException);
        }

        @Test
        @DisplayName("task RuntimeException + cleanup RuntimeException → task 예외가 primary, cleanup은 suppressed")
        void taskFails_cleanupFails_taskIsPrimary_cleanupIsSuppressed() {
            // given
            RuntimeException taskException = new IllegalArgumentException("task failed");
            RuntimeException cleanupException = new IllegalStateException("cleanup failed");
            FinallyPolicy finallyPolicy = new FinallyPolicy(() -> { throw cleanupException; });
            ExecutionPipeline pipeline = new ExecutionPipeline(List.of(finallyPolicy));

            // when & then
            assertThatThrownBy(() -> pipeline.executeRaw(() -> { throw taskException; }, CONTEXT))
                    .isSameAs(taskException)
                    .satisfies(thrown -> {
                        Throwable[] suppressed = thrown.getSuppressed();
                        assertThat(suppressed)
                                .as("cleanup 예외가 suppressed로 보존되어야 함")
                                .hasSize(1);
                        assertThat(suppressed[0]).isSameAs(cleanupException);
                    });
        }

        @Test
        @DisplayName("cleanup이 성공하면 정상 반환")
        void cleanupSuccess_returnsNormally() throws Throwable {
            // given
            AtomicInteger cleanupCount = new AtomicInteger(0);
            FinallyPolicy finallyPolicy = new FinallyPolicy(cleanupCount::incrementAndGet);
            ExecutionPipeline pipeline = new ExecutionPipeline(List.of(finallyPolicy));

            // when
            String result = pipeline.executeRaw(() -> "success", CONTEXT);

            // then
            assertThat(result).isEqualTo("success");
            assertThat(cleanupCount.get())
                    .as("cleanup이 정확히 1회 실행되어야 함")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("task 실패 시에도 cleanup은 반드시 실행됨")
        void taskFails_cleanupStillRuns() {
            // given
            AtomicInteger cleanupCount = new AtomicInteger(0);
            RuntimeException taskException = new IllegalArgumentException("task failed");
            FinallyPolicy finallyPolicy = new FinallyPolicy(cleanupCount::incrementAndGet);
            ExecutionPipeline pipeline = new ExecutionPipeline(List.of(finallyPolicy));

            // when & then
            assertThatThrownBy(() -> pipeline.executeRaw(() -> { throw taskException; }, CONTEXT))
                    .isSameAs(taskException);

            assertThat(cleanupCount.get())
                    .as("task 실패 시에도 cleanup이 정확히 1회 실행되어야 함")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("cleanup Error → Error가 최우선 전파 (task Exception보다 우선)")
        void cleanupError_propagatesImmediately() {
            // given
            RuntimeException taskException = new IllegalArgumentException("task failed");
            OutOfMemoryError cleanupError = new OutOfMemoryError("cleanup OOM");
            FinallyPolicy finallyPolicy = new FinallyPolicy(() -> { throw cleanupError; });
            ExecutionPipeline pipeline = new ExecutionPipeline(List.of(finallyPolicy));

            // when & then
            assertThatThrownBy(() -> pipeline.executeRaw(() -> { throw taskException; }, CONTEXT))
                    .isInstanceOf(OutOfMemoryError.class)
                    .isSameAs(cleanupError);
        }
    }

    @Nested
    @DisplayName("action() 접근자 테스트")
    class ActionAccessorTest {

        @Test
        @DisplayName("action()은 생성자에 전달된 Runnable을 반환해야 한다")
        void action_returnsConstructorRunnable() {
            // given
            AtomicInteger counter = new AtomicInteger(0);
            Runnable cleanupTask = counter::incrementAndGet;
            FinallyPolicy policy = new FinallyPolicy(cleanupTask);

            // when
            Runnable returned = policy.action();

            // then
            assertThat(returned).isSameAs(cleanupTask);

            // 실행 검증
            returned.run();
            assertThat(counter.get()).isEqualTo(1);
        }
    }
}
