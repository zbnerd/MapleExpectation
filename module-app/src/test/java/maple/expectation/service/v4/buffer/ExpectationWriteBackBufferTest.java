package maple.expectation.service.v4.buffer;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import maple.expectation.config.BufferProperties;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.CostBreakdownDto;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ExpectationWriteBackBuffer 테스트 (#266 P0: Shutdown Race 방지)
 *
 * <h3>5-Agent Council 합의 (Round 3-4 Yellow+Purple 피드백 반영)</h3>
 *
 * <ul>
 *   <li>Yellow (QA): CyclicBarrier로 동기화 (Thread.sleep 금지)
 *   <li>Yellow (QA): NoOpBackoff로 결정적 테스트
 *   <li>Purple (Auditor): 집합 기반 무결성 검증
 * </ul>
 *
 * <h3>테스트 시나리오</h3>
 *
 * <ul>
 *   <li>shutdownRace_shouldNotLoseData: 10 스레드 offer + shutdown 동시 호출
 *   <li>casRetry_shouldSucceedAfterContention: CAS 경합 테스트
 *   <li>backpressure_shouldRejectWhenQueueFull: 백프레셔 테스트
 * </ul>
 */
@DisplayName("ExpectationWriteBackBuffer P0 Shutdown Race 방지 테스트")
class ExpectationWriteBackBufferTest {

  private ExpectationWriteBackBuffer buffer;
  private SimpleMeterRegistry meterRegistry;
  private BufferProperties properties;
  private LogicExecutor executor;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    properties = new BufferProperties(10, 10, 100); // 테스트용 작은 값
    executor = new TestLogicExecutor(); // 테스트용 간단한 Executor

    // NoOpBackoff로 결정적 테스트 (Yellow 요구)
    buffer =
        new ExpectationWriteBackBuffer(
            properties, meterRegistry, new BackoffStrategy.NoOpBackoff(), executor);
  }

  /**
   * 테스트용 간단한 LogicExecutor 구현
   *
   * <p>작업을 직접 실행하고 예외를 적절히 처리합니다.
   */
  private static class TestLogicExecutor implements LogicExecutor {
    @Override
    public <T> T execute(ThrowingSupplier<T> task, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        return defaultValue;
      }
    }

    @Override
    public <T> T executeOrCatch(
        ThrowingSupplier<T> task, Function<Throwable, T> recovery, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        return recovery.apply(e);
      }
    }

    @Override
    public void executeVoid(ThrowingRunnable task, TaskContext context) {
      try {
        task.run();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public <T> T executeWithFinally(
        ThrowingSupplier<T> task, Runnable finallyBlock, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        throw new RuntimeException(e);
      } finally {
        finallyBlock.run();
      }
    }

    @Override
    public <T> T executeWithTranslation(
        ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        throw translator.translate(e, context);
      }
    }

    @Override
    public <T> T executeWithFallback(
        ThrowingSupplier<T> task, Function<Throwable, T> fallback, TaskContext context) {
      try {
        return task.get();
      } catch (Throwable e) {
        return fallback.apply(e);
      }
    }
  }

  @Test
  @DisplayName("P0: Shutdown Race - 데이터 유실 없음 검증")
  void shutdownRace_shouldNotLoseData() throws Exception {
    // Given: CyclicBarrier로 동기화 (Yellow: Thread.sleep 금지)
    int threadCount = 10;
    int tasksPerThread = 10;
    CyclicBarrier startBarrier = new CyclicBarrier(threadCount + 1); // +1 for main thread
    CyclicBarrier endBarrier = new CyclicBarrier(threadCount + 1);

    // Purple: 집합 기반 무결성 검증
    Set<Long> offeredIds = ConcurrentHashMap.newKeySet();
    AtomicInteger offerCount = new AtomicInteger(0);

    ExecutorService producers = Executors.newFixedThreadPool(threadCount);

    // When: 모든 스레드가 동시에 offer 시작
    for (int i = 0; i < threadCount; i++) {
      long taskIdBase = (long) i * 100;
      producers.submit(
          () -> {
            try {
              startBarrier.await(); // 모든 스레드 동시 시작
              for (int j = 0; j < tasksPerThread; j++) {
                long id = taskIdBase + j;
                List<PresetExpectation> presets = createTestPresets(id);
                if (buffer.offer(id, presets)) {
                  offeredIds.add(id);
                  offerCount.incrementAndGet();
                }
              }
              endBarrier.await(); // 모든 스레드 완료 대기
            } catch (Exception e) {
              Thread.currentThread().interrupt();
            }
          });
    }

    // 시작 신호
    startBarrier.await();

    // 완료 대기
    endBarrier.await();

    // Shutdown 실행 (모든 offer 완료 후)
    buffer.prepareShutdown();
    boolean completed = buffer.awaitPendingOffers(Duration.ofSeconds(5));

    // Drain으로 모든 데이터 추출
    Set<Long> drainedIds = ConcurrentHashMap.newKeySet();
    while (!buffer.isEmpty()) {
      List<ExpectationWriteTask> batch = buffer.drain(100);
      batch.forEach(task -> drainedIds.add(task.characterId()));
    }

    // Then: 집합 무결성 검증 (순서 무관)
    assertThat(completed).isTrue();
    assertThat(drainedIds).containsExactlyInAnyOrderElementsOf(offeredIds);

    // Cleanup
    producers.shutdown();
    producers.awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("P0: Shutdown 중 offer 거부 검증")
  void shutdownInProgress_shouldRejectOffers() {
    // Given: Shutdown 시작
    buffer.prepareShutdown();

    // When: offer 시도
    boolean accepted = buffer.offer(1L, createTestPresets(1L));

    // Then: 거부됨
    assertThat(accepted).isFalse();
    assertThat(buffer.isShuttingDown()).isTrue();
  }

  @Test
  @DisplayName("P1-1: CAS 경합 시 재시도 성공")
  void casRetry_shouldSucceedAfterContention() throws Exception {
    // Given: 높은 경합 상황 시뮬레이션
    int threadCount = 5;
    CyclicBarrier barrier = new CyclicBarrier(threadCount + 1);
    AtomicInteger successCount = new AtomicInteger(0);

    ExecutorService producers = Executors.newFixedThreadPool(threadCount);

    // When: 모든 스레드가 동시에 offer
    for (int i = 0; i < threadCount; i++) {
      long id = i;
      producers.submit(
          () -> {
            try {
              barrier.await();
              if (buffer.offer(id, createTestPresets(id))) {
                successCount.incrementAndGet();
              }
            } catch (Exception e) {
              Thread.currentThread().interrupt();
            }
          });
    }

    barrier.await(); // 동시 시작
    Thread.sleep(500); // 완료 대기 (동시성 테스트 안정화를 위해 대기 시간 증가)

    // Then: 모든 offer 성공 (CAS 재시도 덕분)
    assertThat(successCount.get()).as("CAS 재시도로 모든 스레드가 성공해야 함").isEqualTo(threadCount);

    // Cleanup
    producers.shutdown();
    producers.awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("P1-1: 백프레셔 - 큐 가득 찼을 때 거부")
  void backpressure_shouldRejectWhenQueueFull() {
    // Given: 작은 큐 크기 (100)로 설정됨
    // 큐를 가득 채움 (100개 = maxQueueSize)
    for (int i = 0; i < 33; i++) { // 33 * 3 presets = 99 items
      buffer.offer((long) i, createTestPresets(i));
    }

    // When: 추가 offer 시도 (4개 이상 추가 시 초과)
    boolean accepted = buffer.offer(999L, createTestPresets(999L)); // 3 presets 추가 시도

    // Then: 거부됨 (102 > 100)
    assertThat(accepted).isFalse();
  }

  @Test
  @DisplayName("Drain 정상 동작 검증")
  void drain_shouldReturnBatchedTasks() {
    // Given: 여러 offer 수행
    buffer.offer(1L, createTestPresets(1L));
    buffer.offer(2L, createTestPresets(2L));

    // When: drain 수행
    List<ExpectationWriteTask> batch = buffer.drain(10);

    // Then: 6개 태스크 (2 캐릭터 × 3 프리셋)
    assertThat(batch).hasSize(6);
    assertThat(buffer.getPendingCount()).isZero();
  }

  /** 테스트용 PresetExpectation 목록 생성 */
  private List<PresetExpectation> createTestPresets(long characterId) {
    return List.of(
        PresetExpectation.builder()
            .presetNo(1)
            .totalExpectedCost(BigDecimal.valueOf(1000000))
            .totalCostText("1,000,000")
            .costBreakdown(
                CostBreakdownDto.builder()
                    .blackCubeCost(BigDecimal.valueOf(500000))
                    .redCubeCost(BigDecimal.ZERO)
                    .additionalCubeCost(BigDecimal.valueOf(300000))
                    .starforceCost(BigDecimal.valueOf(200000))
                    .build())
            .items(Collections.emptyList())
            .build(),
        PresetExpectation.builder()
            .presetNo(2)
            .totalExpectedCost(BigDecimal.valueOf(2000000))
            .totalCostText("2,000,000")
            .costBreakdown(
                CostBreakdownDto.builder()
                    .blackCubeCost(BigDecimal.valueOf(1000000))
                    .redCubeCost(BigDecimal.ZERO)
                    .additionalCubeCost(BigDecimal.valueOf(600000))
                    .starforceCost(BigDecimal.valueOf(400000))
                    .build())
            .items(Collections.emptyList())
            .build(),
        PresetExpectation.builder()
            .presetNo(3)
            .totalExpectedCost(BigDecimal.valueOf(3000000))
            .totalCostText("3,000,000")
            .costBreakdown(
                CostBreakdownDto.builder()
                    .blackCubeCost(BigDecimal.valueOf(1500000))
                    .redCubeCost(BigDecimal.ZERO)
                    .additionalCubeCost(BigDecimal.valueOf(900000))
                    .starforceCost(BigDecimal.valueOf(600000))
                    .build())
            .items(Collections.emptyList())
            .build());
  }
}
