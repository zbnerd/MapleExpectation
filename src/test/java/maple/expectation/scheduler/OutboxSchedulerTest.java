package maple.expectation.scheduler;

import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.service.v2.donation.outbox.OutboxProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * OutboxScheduler 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 * <p>스케줄러 메서드를 직접 호출하여 OutboxProcessor 호출 여부를 검증합니다.</p>
 *
 * <h4>테스트 범위</h4>
 * <ul>
 *   <li>pollAndProcess: Outbox 폴링 및 처리</li>
 *   <li>recoverStalled: Stalled 상태 복구</li>
 * </ul>
 */
@Tag("unit")
class OutboxSchedulerTest {

    private OutboxProcessor outboxProcessor;
    private LogicExecutor executor;
    private OutboxScheduler scheduler;

    @BeforeEach
    void setUp() {
        outboxProcessor = mock(OutboxProcessor.class);
        executor = createMockLogicExecutor();
        scheduler = new OutboxScheduler(outboxProcessor, executor);
    }

    @Nested
    @DisplayName("pollAndProcess")
    class PollAndProcessTest {

        @Test
        @DisplayName("OutboxProcessor.pollAndProcess 호출")
        void shouldCallOutboxProcessor() {
            // when
            scheduler.pollAndProcess();

            // then
            verify(outboxProcessor, times(1)).pollAndProcess();
        }

        @Test
        @DisplayName("LogicExecutor.executeVoid를 통해 실행")
        void shouldExecuteThroughLogicExecutor() {
            // when
            scheduler.pollAndProcess();

            // then
            verify(executor).executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));
        }

        @Test
        @DisplayName("TaskContext에 올바른 컨텍스트 전달")
        void shouldPassCorrectTaskContext() {
            // when
            scheduler.pollAndProcess();

            // then
            verify(executor).executeVoid(any(ThrowingRunnable.class), argThat((TaskContext context) ->
                    context.component().equals("Scheduler") &&
                    context.operation().equals("Outbox.Poll")
            ));
        }
    }

    @Nested
    @DisplayName("recoverStalled")
    class RecoverStalledTest {

        @Test
        @DisplayName("OutboxProcessor.recoverStalled 호출")
        void shouldCallRecoverStalled() {
            // when
            scheduler.recoverStalled();

            // then
            verify(outboxProcessor, times(1)).recoverStalled();
        }

        @Test
        @DisplayName("LogicExecutor.executeVoid를 통해 실행")
        void shouldExecuteThroughLogicExecutor() {
            // when
            scheduler.recoverStalled();

            // then
            verify(executor).executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));
        }

        @Test
        @DisplayName("TaskContext에 올바른 컨텍스트 전달")
        void shouldPassCorrectTaskContext() {
            // when
            scheduler.recoverStalled();

            // then
            verify(executor).executeVoid(any(ThrowingRunnable.class), argThat((TaskContext context) ->
                    context.component().equals("Scheduler") &&
                    context.operation().equals("Outbox.RecoverStalled")
            ));
        }
    }

    @Nested
    @DisplayName("예외 처리")
    class ExceptionHandlingTest {

        @Test
        @DisplayName("pollAndProcess 중 예외 발생 시 LogicExecutor가 처리")
        void whenPollThrowsException_shouldBeHandledByExecutor() {
            // given
            doThrow(new RuntimeException("Poll failed")).when(outboxProcessor).pollAndProcess();

            // when
            scheduler.pollAndProcess();

            // then - LogicExecutor가 예외를 처리하므로 예외가 전파되지 않음
            verify(outboxProcessor).pollAndProcess();
        }

        @Test
        @DisplayName("recoverStalled 중 예외 발생 시 LogicExecutor가 처리")
        void whenRecoverThrowsException_shouldBeHandledByExecutor() {
            // given
            doThrow(new RuntimeException("Recovery failed")).when(outboxProcessor).recoverStalled();

            // when
            scheduler.recoverStalled();

            // then - LogicExecutor가 예외를 처리하므로 예외가 전파되지 않음
            verify(outboxProcessor).recoverStalled();
        }
    }

    // ==================== Helper Methods ====================

    private LogicExecutor createMockLogicExecutor() {
        LogicExecutor mockExecutor = mock(LogicExecutor.class);

        // executeVoid: ThrowingRunnable 실행 (예외 발생해도 처리)
        doAnswer(invocation -> {
            ThrowingRunnable task = invocation.getArgument(0);
            try {
                task.run();
            } catch (Throwable e) {
                // LogicExecutor가 예외 처리
            }
            return null;
        }).when(mockExecutor).executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));

        return mockExecutor;
    }
}
