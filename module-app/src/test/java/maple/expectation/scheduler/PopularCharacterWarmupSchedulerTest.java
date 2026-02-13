package maple.expectation.scheduler;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.function.Function;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.lock.LockStrategy;
import maple.expectation.service.v4.EquipmentExpectationServiceV4;
import maple.expectation.service.v4.warmup.PopularCharacterTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PopularCharacterWarmupScheduler 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>웜업 스케줄러 메서드를 직접 호출하여 동작을 검증합니다.
 *
 * <h4>테스트 범위</h4>
 *
 * <ul>
 *   <li>dailyWarmup: 매일 새벽 5시 웜업
 *   <li>initialWarmup: 서버 시작 후 30초 웜업
 *   <li>분산 락 사용
 *   <li>인기 캐릭터 조회 및 캐시 프리로딩
 * </ul>
 */
@Tag("unit")
class PopularCharacterWarmupSchedulerTest {

  private PopularCharacterTracker popularCharacterTracker;
  private EquipmentExpectationServiceV4 expectationService;
  private LockStrategy lockStrategy;
  private LogicExecutor executor;
  private MeterRegistry meterRegistry;
  private PopularCharacterWarmupScheduler scheduler;

  @BeforeEach
  void setUp() {
    popularCharacterTracker = mock(PopularCharacterTracker.class);
    expectationService = mock(EquipmentExpectationServiceV4.class);
    lockStrategy = mock(LockStrategy.class);
    executor = createMockLogicExecutor();
    meterRegistry = new SimpleMeterRegistry(); // 실제 MeterRegistry 사용

    scheduler =
        new PopularCharacterWarmupScheduler(
            popularCharacterTracker, expectationService, lockStrategy, executor, meterRegistry);

    // Set @Value fields via reflection
    ReflectionTestUtils.setField(scheduler, "topCount", 50);
    ReflectionTestUtils.setField(scheduler, "delayBetweenMs", 0L); // 테스트에서는 지연 없음
  }

  @Nested
  @DisplayName("dailyWarmup")
  class DailyWarmupTest {

    @Test
    @DisplayName("분산 락 획득 후 웜업 실행")
    void shouldExecuteWarmupWithLock() throws Throwable {
      // given
      given(
              lockStrategy.executeWithLock(
                  anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
          .willAnswer(
              invocation -> {
                ThrowingSupplier<?> supplier = invocation.getArgument(3);
                return supplier.get();
              });
      given(popularCharacterTracker.getYesterdayTopCharacters(50))
          .willReturn(List.of("User1", "User2"));

      // when
      scheduler.dailyWarmup();

      // then
      verify(lockStrategy)
          .executeWithLock(
              eq("popular-warmup-lock"), eq(0L), eq(300L), any(ThrowingSupplier.class));
    }

    @Test
    @DisplayName("락 획득 실패 시 스킵")
    void whenLockFailed_shouldSkip() throws Throwable {
      // given
      given(
              lockStrategy.executeWithLock(
                  anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
          .willThrow(new DistributedLockException("Lock failed"));

      // when
      scheduler.dailyWarmup();

      // then
      verify(popularCharacterTracker, never()).getYesterdayTopCharacters(anyInt());
    }
  }

  @Nested
  @DisplayName("initialWarmup")
  class InitialWarmupTest {

    @Test
    @DisplayName("서버 시작 후 웜업 실행")
    void shouldExecuteInitialWarmup() throws Throwable {
      // given
      given(
              lockStrategy.executeWithLock(
                  anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
          .willAnswer(
              invocation -> {
                ThrowingSupplier<?> supplier = invocation.getArgument(3);
                return supplier.get();
              });
      given(popularCharacterTracker.getYesterdayTopCharacters(50)).willReturn(List.of("InitUser1"));

      // when
      scheduler.initialWarmup();

      // then
      verify(lockStrategy)
          .executeWithLock(
              eq("popular-warmup-lock"), anyLong(), anyLong(), any(ThrowingSupplier.class));
    }
  }

  @Nested
  @DisplayName("웜업 로직")
  class WarmupLogicTest {

    @Test
    @DisplayName("인기 캐릭터 목록 조회")
    void shouldGetTopCharacters() throws Throwable {
      // given
      given(
              lockStrategy.executeWithLock(
                  anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
          .willAnswer(
              invocation -> {
                ThrowingSupplier<?> supplier = invocation.getArgument(3);
                return supplier.get();
              });
      given(popularCharacterTracker.getYesterdayTopCharacters(50))
          .willReturn(List.of("Top1", "Top2", "Top3"));

      // when
      scheduler.dailyWarmup();

      // then
      verify(popularCharacterTracker).getYesterdayTopCharacters(50);
    }

    @Test
    @DisplayName("각 캐릭터에 대해 calculateExpectation 호출")
    void shouldWarmupEachCharacter() throws Throwable {
      // given
      List<String> topCharacters = List.of("Char1", "Char2", "Char3");
      given(
              lockStrategy.executeWithLock(
                  anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
          .willAnswer(
              invocation -> {
                ThrowingSupplier<?> supplier = invocation.getArgument(3);
                return supplier.get();
              });
      given(popularCharacterTracker.getYesterdayTopCharacters(50)).willReturn(topCharacters);

      // when
      scheduler.dailyWarmup();

      // then - 각 캐릭터에 대해 호출
      verify(expectationService, times(3)).calculateExpectation(anyString(), eq(false));
    }

    @Test
    @DisplayName("인기 캐릭터가 없으면 웜업 스킵")
    void whenNoCharacters_shouldSkipWarmup() throws Throwable {
      // given
      given(
              lockStrategy.executeWithLock(
                  anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
          .willAnswer(
              invocation -> {
                ThrowingSupplier<?> supplier = invocation.getArgument(3);
                return supplier.get();
              });
      given(popularCharacterTracker.getYesterdayTopCharacters(50)).willReturn(List.of());

      // when
      scheduler.dailyWarmup();

      // then
      verify(expectationService, never()).calculateExpectation(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("개별 캐릭터 웜업 실패 시 다음 캐릭터 계속 처리")
    void whenCharacterFails_shouldContinueWithNext() throws Throwable {
      // given
      given(
              lockStrategy.executeWithLock(
                  anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
          .willAnswer(
              invocation -> {
                ThrowingSupplier<?> supplier = invocation.getArgument(3);
                return supplier.get();
              });
      given(popularCharacterTracker.getYesterdayTopCharacters(50))
          .willReturn(List.of("Fail1", "Success2", "Success3"));

      // 첫 번째 호출은 예외, 나머지는 성공
      given(expectationService.calculateExpectation(eq("Fail1"), eq(false)))
          .willThrow(new RuntimeException("API Error"));
      given(expectationService.calculateExpectation(eq("Success2"), eq(false))).willReturn(null);
      given(expectationService.calculateExpectation(eq("Success3"), eq(false))).willReturn(null);

      // when
      scheduler.dailyWarmup();

      // then - 모든 캐릭터에 대해 시도
      verify(expectationService, times(3)).calculateExpectation(anyString(), eq(false));
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

    // executeOrDefault: ThrowingSupplier 실행, 예외 시 기본값 반환
    given(mockExecutor.executeOrDefault(any(ThrowingSupplier.class), any(), any(TaskContext.class)))
        .willAnswer(
            invocation -> {
              ThrowingSupplier<?> task = invocation.getArgument(0);
              Object defaultValue = invocation.getArgument(1);
              try {
                return task.get();
              } catch (Throwable e) {
                return defaultValue;
              }
            });

    return mockExecutor;
  }
}
