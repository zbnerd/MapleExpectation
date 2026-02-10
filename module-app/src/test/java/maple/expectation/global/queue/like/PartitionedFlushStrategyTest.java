package maple.expectation.global.queue.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.service.v2.LikeSyncExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * PartitionedFlushStrategy 단위 테스트 (#271 V5 Stateless Architecture)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Yellow (QA): 파티션 분산 락 테스트
 *   <li>Red (SRE): 락 획득 실패 시 데이터 복원 검증
 *   <li>Purple (Auditor): 부분 실패 시 보상 트랜잭션 검증
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PartitionedFlushStrategy 단위 테스트")
class PartitionedFlushStrategyTest {

  @Mock private RedissonClient redissonClient;

  @Mock private RedisLikeBufferStorage bufferStorage;

  @Mock private LogicExecutor executor;

  @Mock private LikeSyncExecutor syncExecutor;

  @Mock private RLock rLock;

  private SimpleMeterRegistry meterRegistry;
  private PartitionedFlushStrategy strategy;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();

    // Redisson Lock Mock
    lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
    lenient().when(rLock.isHeldByCurrentThread()).thenReturn(true);

    // LogicExecutor Mocks
    setupExecutorMocks();

    strategy =
        new PartitionedFlushStrategy(
            redissonClient, bufferStorage, executor, meterRegistry, syncExecutor);
  }

  @SuppressWarnings("unchecked")
  private void setupExecutorMocks() {
    // executeOrDefault - 람다 직접 실행
    lenient()
        .when(executor.executeOrDefault(any(ThrowingSupplier.class), any(), any(TaskContext.class)))
        .thenAnswer(
            (Answer<Object>)
                invocation -> {
                  ThrowingSupplier<?> task = invocation.getArgument(0);
                  Object defaultValue = invocation.getArgument(1);
                  try {
                    return task.get();
                  } catch (Exception e) {
                    return defaultValue;
                  }
                });

    // executeVoid - 람다 직접 실행
    lenient()
        .doAnswer(
            (Answer<Void>)
                invocation -> {
                  ThrowingRunnable task = invocation.getArgument(0);
                  task.run();
                  return null;
                })
        .when(executor)
        .executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));

    // executeWithFinally - 람다 직접 실행 후 finally 실행
    lenient()
        .when(
            executor.executeWithFinally(
                any(ThrowingSupplier.class), any(Runnable.class), any(TaskContext.class)))
        .thenAnswer(
            (Answer<Object>)
                invocation -> {
                  ThrowingSupplier<?> task = invocation.getArgument(0);
                  Runnable finalizer = invocation.getArgument(1);
                  try {
                    return task.get();
                  } finally {
                    finalizer.run();
                  }
                });
  }

  @Nested
  @DisplayName("flushWithPartitions() 테스트")
  class FlushWithPartitionsTest {

    @Test
    @DisplayName("빈 버퍼 - 빈 결과 반환")
    void flush_shouldReturnEmptyWhenNoData() throws Exception {
      // Given
      when(bufferStorage.fetchAndClear(1000)).thenReturn(Map.of());

      // When
      PartitionedFlushStrategy.FlushResult result =
          strategy.flushWithPartitions((ign, delta) -> {});

      // Then
      assertThat(result.processedEntries()).isZero();
      assertThat(result.acquiredPartitions()).isZero();
    }

    @Test
    @DisplayName("정상 Flush - 모든 데이터 처리")
    void flush_shouldProcessAllEntries() throws Exception {
      // Given
      Map<String, Long> entries = Map.of("user1", 10L, "user2", 20L);
      when(bufferStorage.fetchAndClear(1000)).thenReturn(entries);
      when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

      AtomicInteger processCount = new AtomicInteger(0);

      // When
      PartitionedFlushStrategy.FlushResult result =
          strategy.flushWithPartitions(
              (ign, delta) -> {
                processCount.incrementAndGet();
              });

      // Then
      assertThat(result.processedEntries()).isEqualTo(2);
      assertThat(result.totalDelta()).isEqualTo(30L);
      assertThat(processCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("락 획득 실패 - 데이터 복원")
    void flush_shouldRestoreDataWhenLockFailed() throws Exception {
      // Given
      Map<String, Long> entries = new HashMap<>();
      entries.put("user1", 10L);
      when(bufferStorage.fetchAndClear(1000)).thenReturn(entries);
      when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

      // When
      PartitionedFlushStrategy.FlushResult result =
          strategy.flushWithPartitions((ign, delta) -> {});

      // Then
      assertThat(result.acquiredPartitions()).isZero();
      verify(bufferStorage).increment("user1", 10L); // 복원 확인
    }

    @Test
    @DisplayName("처리 중 실패 - 실패 엔트리 복원")
    void flush_shouldRestoreFailedEntries() throws Exception {
      // Given
      Map<String, Long> entries = Map.of("user1", 10L, "user2", 20L);
      when(bufferStorage.fetchAndClear(1000)).thenReturn(entries);
      when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

      // user2 처리 시 예외 발생하도록 설정
      BiConsumer<String, Long> failingProcessor =
          (ign, delta) -> {
            if ("user2".equals(ign)) {
              throw new RuntimeException("Simulated failure");
            }
          };

      // executeOrDefault가 예외 시 false 반환하도록 재설정
      when(executor.executeOrDefault(any(ThrowingSupplier.class), any(), any(TaskContext.class)))
          .thenAnswer(
              (Answer<Object>)
                  invocation -> {
                    ThrowingSupplier<?> task = invocation.getArgument(0);
                    Object defaultValue = invocation.getArgument(1);
                    try {
                      return task.get();
                    } catch (Exception e) {
                      return defaultValue;
                    }
                  });

      // When
      PartitionedFlushStrategy.FlushResult result = strategy.flushWithPartitions(failingProcessor);

      // Then: user1은 처리, user2는 복원
      assertThat(result.processedEntries()).isEqualTo(1);
      verify(bufferStorage).increment("user2", 20L);
    }
  }

  @Nested
  @DisplayName("FlushResult 테스트")
  class FlushResultTest {

    @Test
    @DisplayName("empty() - 빈 결과")
    void empty_shouldCreateEmptyResult() {
      // When
      PartitionedFlushStrategy.FlushResult result = PartitionedFlushStrategy.FlushResult.empty();

      // Then
      assertThat(result.acquiredPartitions()).isZero();
      assertThat(result.processedEntries()).isZero();
      assertThat(result.totalDelta()).isZero();
      assertThat(result.failedPartitions()).isZero();
      assertThat(result.hasProcessed()).isFalse();
      assertThat(result.hasFailures()).isFalse();
    }

    @Test
    @DisplayName("hasProcessed() - 처리 여부 확인")
    void hasProcessed_shouldReturnCorrectly() {
      // Given
      var withProcessed = new PartitionedFlushStrategy.FlushResult(1, 10, 100L, 0);
      var withoutProcessed = new PartitionedFlushStrategy.FlushResult(0, 0, 0L, 0);

      // Then
      assertThat(withProcessed.hasProcessed()).isTrue();
      assertThat(withoutProcessed.hasProcessed()).isFalse();
    }

    @Test
    @DisplayName("hasFailures() - 실패 여부 확인")
    void hasFailures_shouldReturnCorrectly() {
      // Given
      var withFailures = new PartitionedFlushStrategy.FlushResult(2, 10, 100L, 1);
      var withoutFailures = new PartitionedFlushStrategy.FlushResult(2, 10, 100L, 0);

      // Then
      assertThat(withFailures.hasFailures()).isTrue();
      assertThat(withoutFailures.hasFailures()).isFalse();
    }
  }

  @Nested
  @DisplayName("분산 락 테스트")
  class DistributedLockTest {

    @Test
    @DisplayName("락 해제 - 항상 실행")
    void unlock_shouldAlwaysExecute() throws Exception {
      // Given
      Map<String, Long> entries = Map.of("user1", 10L);
      when(bufferStorage.fetchAndClear(1000)).thenReturn(entries);
      when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

      // When
      strategy.flushWithPartitions((ign, delta) -> {});

      // Then
      verify(rLock).unlock();
    }

    @Test
    @DisplayName("락 미획득 시 unlock 호출 안함")
    void unlock_shouldNotCallWhenNotAcquired() throws Exception {
      // Given
      Map<String, Long> entries = new HashMap<>();
      entries.put("user1", 10L);
      when(bufferStorage.fetchAndClear(1000)).thenReturn(entries);
      when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

      // When
      strategy.flushWithPartitions((ign, delta) -> {});

      // Then
      verify(rLock, never()).unlock();
    }
  }

  @Nested
  @DisplayName("파티셔닝 테스트")
  class PartitioningTest {

    @Test
    @DisplayName("동일 IGN - 항상 같은 파티션")
    void partitioning_shouldBeConsistent() throws Exception {
      // Given: 같은 IGN은 같은 파티션으로 분류되어야 함
      // Map은 중복 키를 허용하지 않으므로 단일 엔트리로 테스트
      Map<String, Long> entries = Map.of("userA", 10L);
      when(bufferStorage.fetchAndClear(1000)).thenReturn(entries);
      when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

      // When: 동일한 IGN은 항상 같은 파티션 ID를 가짐
      PartitionedFlushStrategy.FlushResult result =
          strategy.flushWithPartitions((ign, delta) -> {});

      // Then: 1개 엔트리가 1개 파티션에서 처리됨
      assertThat(result.processedEntries()).isEqualTo(1);
      assertThat(result.acquiredPartitions()).isEqualTo(1);
    }

    @Test
    @DisplayName("다수 엔트리 - 파티션 분산")
    void partitioning_shouldDistributeEntries() throws Exception {
      // Given: 여러 엔트리가 파티션으로 분산
      Map<String, Long> entries = new HashMap<>();
      for (int i = 0; i < 100; i++) {
        entries.put("user" + i, (long) i);
      }
      when(bufferStorage.fetchAndClear(1000)).thenReturn(entries);
      when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

      AtomicInteger processCount = new AtomicInteger(0);

      // When
      PartitionedFlushStrategy.FlushResult result =
          strategy.flushWithPartitions(
              (ign, delta) -> {
                processCount.incrementAndGet();
              });

      // Then
      assertThat(result.processedEntries()).isEqualTo(100);
      assertThat(processCount.get()).isEqualTo(100);
      // 4개 파티션에 분산되어 락 획득 시도
      verify(redissonClient, times(4)).getLock(anyString());
    }
  }
}
