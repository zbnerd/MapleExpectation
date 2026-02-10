package maple.expectation.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import maple.expectation.config.OutboxProperties;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.service.v2.donation.outbox.OutboxMetrics;
import maple.expectation.service.v2.donation.outbox.OutboxProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * OutboxScheduler 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>스케줄러 메서드를 직접 호출하여 OutboxProcessor 호출 여부를 검증합니다.
 *
 * <h4>P1-7 반영: updatePendingCount() 스케줄러 레벨 호출 검증</h4>
 *
 * <h4>Issue #N19: Outbox 크기 모니터링 검증</h4>
 */
@Tag("unit")
class OutboxSchedulerTest {

  private OutboxProcessor outboxProcessor;
  private OutboxMetrics outboxMetrics;
  private LogicExecutor executor;
  private OutboxProperties properties;
  private OutboxScheduler scheduler;

  @BeforeEach
  void setUp() {
    outboxProcessor = mock(OutboxProcessor.class);
    outboxMetrics = mock(OutboxMetrics.class);
    executor = createMockLogicExecutor();
    properties = mock(OutboxProperties.class);
    when(properties.getSizeAlertThreshold()).thenReturn(1000);
    when(outboxMetrics.getCurrentSize()).thenReturn(500L);

    scheduler = new OutboxScheduler(outboxProcessor, outboxMetrics, executor, properties);
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
      verify(executor)
          .executeVoid(
              any(ThrowingRunnable.class),
              argThat(
                  (TaskContext context) ->
                      context.component().equals("Scheduler")
                          && context.operation().equals("Outbox.Poll")));
    }

    @Test
    @DisplayName("P1-7: pollAndProcess 후 updatePendingCount 호출")
    void shouldUpdatePendingCountAfterPoll() {
      // when
      scheduler.pollAndProcess();

      // then
      verify(outboxMetrics).updatePendingCount();
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
      verify(executor)
          .executeVoid(
              any(ThrowingRunnable.class),
              argThat(
                  (TaskContext context) ->
                      context.component().equals("Scheduler")
                          && context.operation().equals("Outbox.RecoverStalled")));
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

  @Nested
  @DisplayName("monitorOutboxSize - Issue #N19")
  class MonitorOutboxSizeTest {

    @Test
    @DisplayName("OutboxMetrics.updateTotalCount 호출")
    void shouldCallUpdateTotalCount() {
      // when
      scheduler.monitorOutboxSize();

      // then
      verify(outboxMetrics, times(1)).updateTotalCount();
    }

    @Test
    @DisplayName("LogicExecutor.executeVoid를 통해 실행")
    void shouldExecuteThroughLogicExecutor() {
      // when
      scheduler.monitorOutboxSize();

      // then
      verify(executor).executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));
    }

    @Test
    @DisplayName("TaskContext에 올바른 컨텍스트 전달")
    void shouldPassCorrectTaskContext() {
      // when
      scheduler.monitorOutboxSize();

      // then
      verify(executor)
          .executeVoid(
              any(ThrowingRunnable.class),
              argThat(
                  (TaskContext context) ->
                      context.component().equals("Scheduler")
                          && context.operation().equals("Outbox.MonitorSize")));
    }

    @Test
    @DisplayName("임계값 초과 시 getCurrentSize 호출")
    void shouldCallGetCurrentSizeWhenThresholdExceeded() {
      // given
      when(outboxMetrics.getCurrentSize()).thenReturn(1500L);

      // when
      scheduler.monitorOutboxSize();

      // then
      verify(outboxMetrics).getCurrentSize();
      verify(properties).getSizeAlertThreshold();
    }
  }

  // ==================== Helper Methods ====================

  private LogicExecutor createMockLogicExecutor() {
    LogicExecutor mockExecutor = mock(LogicExecutor.class);

    // executeVoid: ThrowingRunnable 실행 (예외 발생해도 처리)
    doAnswer(
            invocation -> {
              ThrowingRunnable task = invocation.getArgument(0);
              try {
                task.run();
              } catch (Throwable e) {
                // LogicExecutor가 예외 처리
              }
              return null;
            })
        .when(mockExecutor)
        .executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));

    return mockExecutor;
  }
}
