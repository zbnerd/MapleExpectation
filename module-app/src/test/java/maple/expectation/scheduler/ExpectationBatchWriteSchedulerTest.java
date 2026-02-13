package maple.expectation.scheduler;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.lock.LockStrategy;
import maple.expectation.repository.v2.EquipmentExpectationSummaryRepository;
import maple.expectation.service.v4.buffer.ExpectationWriteBackBuffer;
import maple.expectation.service.v4.buffer.ExpectationWriteTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ExpectationBatchWriteScheduler 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>스케줄러 flush 메서드를 직접 호출하여 동작을 검증합니다.
 *
 * <h4>테스트 범위</h4>
 *
 * <ul>
 *   <li>flush: 배치 DB 동기화
 *   <li>Shutdown 중 스킵
 *   <li>분산 락 사용
 *   <li>빈 버퍼 스킵
 * </ul>
 */
@Tag("unit")
class ExpectationBatchWriteSchedulerTest {

  private ExpectationWriteBackBuffer buffer;
  private EquipmentExpectationSummaryRepository repository;
  private LockStrategy lockStrategy;
  private LogicExecutor executor;
  private MeterRegistry meterRegistry;
  private ExpectationBatchWriteScheduler scheduler;

  @BeforeEach
  void setUp() {
    buffer = mock(ExpectationWriteBackBuffer.class);
    repository = mock(EquipmentExpectationSummaryRepository.class);
    lockStrategy = mock(LockStrategy.class);
    executor = createMockLogicExecutor();
    meterRegistry = mock(MeterRegistry.class);

    Counter counter = mock(Counter.class);
    given(meterRegistry.counter(anyString())).willReturn(counter);

    maple.expectation.config.BatchProperties batchProperties =
        maple.expectation.config.BatchProperties.defaults();

    scheduler =
        new ExpectationBatchWriteScheduler(
            buffer, repository, lockStrategy, executor, meterRegistry, batchProperties);
  }

  @Nested
  @DisplayName("flush")
  class FlushTest {

    @Test
    @DisplayName("Shutdown 중이면 스킵")
    void whenShuttingDown_shouldSkip() throws Throwable {
      // given
      given(buffer.isShuttingDown()).willReturn(true);

      // when
      scheduler.flush();

      // then
      verify(buffer).isShuttingDown();
      verify(buffer, never()).isEmpty();
      verify(lockStrategy, never()).executeWithLock(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("버퍼가 비어있으면 스킵")
    void whenBufferEmpty_shouldSkip() throws Throwable {
      // given
      given(buffer.isShuttingDown()).willReturn(false);
      given(buffer.isEmpty()).willReturn(true);

      // when
      scheduler.flush();

      // then
      verify(buffer).isEmpty();
      verify(lockStrategy, never()).executeWithLock(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("버퍼에 데이터가 있으면 분산 락 획득 후 플러시")
    void whenBufferHasData_shouldFlushWithLock() throws Throwable {
      // given
      given(buffer.isShuttingDown()).willReturn(false);
      given(buffer.isEmpty()).willReturn(false);
      given(buffer.drain(100))
          .willReturn(
              List.of(
                  new ExpectationWriteTask(
                      1L,
                      1,
                      BigDecimal.valueOf(1000),
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      LocalDateTime.now())));
      given(buffer.getPendingCount()).willReturn(0);
      given(
              lockStrategy.executeWithLock(
                  anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
          .willAnswer(
              invocation -> {
                ThrowingSupplier<?> supplier = invocation.getArgument(3);
                return supplier.get();
              });

      // when
      scheduler.flush();

      // then
      verify(lockStrategy)
          .executeWithLock(
              eq("expectation-batch-sync-lock"), eq(0L), eq(10L), any(ThrowingSupplier.class));
    }

    @Test
    @DisplayName("분산 락 획득 실패 시 로그만 남기고 종료")
    void whenLockFailed_shouldLogAndReturn() throws Throwable {
      // given
      given(buffer.isShuttingDown()).willReturn(false);
      given(buffer.isEmpty()).willReturn(false);
      given(
              lockStrategy.executeWithLock(
                  anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
          .willThrow(new DistributedLockException("Lock failed"));

      // when
      scheduler.flush();

      // then - 예외가 전파되지 않음
      verify(lockStrategy).executeWithLock(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("배치 플러시 시 각 태스크 upsert 호출")
    void whenFlush_shouldUpsertEachTask() throws Throwable {
      // given
      given(buffer.isShuttingDown()).willReturn(false);
      given(buffer.isEmpty()).willReturn(false);
      given(buffer.drain(100))
          .willReturn(
              List.of(
                  new ExpectationWriteTask(
                      1L,
                      1,
                      BigDecimal.valueOf(1000),
                      BigDecimal.valueOf(100),
                      BigDecimal.valueOf(200),
                      BigDecimal.valueOf(300),
                      BigDecimal.valueOf(400),
                      LocalDateTime.now()),
                  new ExpectationWriteTask(
                      2L,
                      2,
                      BigDecimal.valueOf(2000),
                      BigDecimal.valueOf(500),
                      BigDecimal.valueOf(600),
                      BigDecimal.valueOf(700),
                      BigDecimal.valueOf(200),
                      LocalDateTime.now())));
      given(buffer.getPendingCount()).willReturn(0);
      given(
              lockStrategy.executeWithLock(
                  anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
          .willAnswer(
              invocation -> {
                ThrowingSupplier<?> supplier = invocation.getArgument(3);
                return supplier.get();
              });

      // when
      scheduler.flush();

      // then - 2개의 upsert 호출 예상
      verify(repository, times(2))
          .upsertExpectationSummary(
              anyLong(),
              anyInt(),
              any(BigDecimal.class),
              any(BigDecimal.class),
              any(BigDecimal.class),
              any(BigDecimal.class),
              any(BigDecimal.class));
    }
  }

  // ==================== Helper Methods ====================

  @SuppressWarnings("unchecked")
  private LogicExecutor createMockLogicExecutor() {
    LogicExecutor mockExecutor = mock(LogicExecutor.class);

    // executeOrCatch: ThrowingSupplier 실행, 예외 시 recovery 호출
    given(
            mockExecutor.executeOrCatch(
                any(ThrowingSupplier.class), any(Function.class), any(TaskContext.class)))
        .willAnswer(
            invocation -> {
              ThrowingSupplier<?> task = invocation.getArgument(0);
              Function<Throwable, ?> recovery = invocation.getArgument(1);
              try {
                return task.get();
              } catch (Throwable e) {
                return recovery.apply(e);
              }
            });

    return mockExecutor;
  }
}
