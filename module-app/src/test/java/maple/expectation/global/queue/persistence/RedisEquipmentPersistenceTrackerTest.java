package maple.expectation.global.queue.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.global.queue.RedisKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

/**
 * RedisEquipmentPersistenceTracker 단위 테스트 (#271 V5 Stateless Architecture)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Yellow (QA): Mock 기반 단위 테스트로 빠른 피드백
 *   <li>Purple (Auditor): 분산 추적 + 로컬 Future 관리 검증
 *   <li>Red (SRE): Shutdown Race Prevention 검증
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisEquipmentPersistenceTracker 단위 테스트")
class RedisEquipmentPersistenceTrackerTest {

  @Mock private RedissonClient redissonClient;

  @Mock private LogicExecutor executor;

  @Mock private RSet<String> rSet;

  private SimpleMeterRegistry meterRegistry;
  private RedisEquipmentPersistenceTracker tracker;

  // 시뮬레이션용 로컬 Set (Redis SET 대체)
  private Set<String> simulatedRedisSet;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    simulatedRedisSet = ConcurrentHashMap.newKeySet();

    // Redisson Mocks
    setupRedissonMocks();

    // LogicExecutor Mocks
    setupExecutorMocks();

    tracker = new RedisEquipmentPersistenceTracker(redissonClient, executor, meterRegistry);
  }

  @SuppressWarnings("unchecked")
  private void setupRedissonMocks() {
    lenient().when(redissonClient.getSet(anyString())).thenReturn((RSet) rSet);

    // RSet 동작 시뮬레이션
    lenient()
        .when(rSet.add(anyString()))
        .thenAnswer(
            inv -> {
              String ocid = inv.getArgument(0);
              return simulatedRedisSet.add(ocid);
            });

    lenient()
        .when(rSet.remove(anyString()))
        .thenAnswer(
            inv -> {
              String ocid = inv.getArgument(0);
              return simulatedRedisSet.remove(ocid);
            });

    lenient()
        .when(rSet.contains(anyString()))
        .thenAnswer(
            inv -> {
              String ocid = inv.getArgument(0);
              return simulatedRedisSet.contains(ocid);
            });

    lenient().when(rSet.readAll()).thenAnswer(inv -> new HashSet<>(simulatedRedisSet));

    lenient().when(rSet.size()).thenAnswer(inv -> simulatedRedisSet.size());

    lenient()
        .doAnswer(
            inv -> {
              simulatedRedisSet.clear();
              return null;
            })
        .when(rSet)
        .clear();
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

    // executeWithFallback - 람다 직접 실행
    lenient()
        .when(
            executor.executeWithFallback(
                any(ThrowingSupplier.class), any(), any(TaskContext.class)))
        .thenAnswer(
            (Answer<Object>)
                invocation -> {
                  ThrowingSupplier<?> task = invocation.getArgument(0);
                  try {
                    return task.get();
                  } catch (Exception e) {
                    java.util.function.Function<Throwable, ?> fallback = invocation.getArgument(1);
                    return fallback.apply(e);
                  }
                });
  }

  @Nested
  @DisplayName("trackOperation() 테스트")
  class TrackOperationTest {

    @Test
    @DisplayName("정상 등록 - Redis와 로컬에 추적")
    void trackOperation_shouldTrackBothRedisAndLocal() {
      // Given
      CompletableFuture<Void> future = new CompletableFuture<>();

      // When
      tracker.trackOperation("ocid123", future);

      // Then
      assertThat(tracker.getLocalPendingCount()).isEqualTo(1);
      assertThat(simulatedRedisSet).contains("ocid123");
    }

    @Test
    @DisplayName("작업 완료 시 자동 정리")
    void trackOperation_shouldCleanupOnCompletion() {
      // Given
      CompletableFuture<Void> future = new CompletableFuture<>();
      tracker.trackOperation("ocid123", future);
      assertThat(tracker.getLocalPendingCount()).isEqualTo(1);

      // When
      future.complete(null);

      // Then (약간의 지연 후 확인 - whenComplete 비동기 실행)
      assertThat(tracker.getLocalPendingOcids()).doesNotContain("ocid123");
    }

    @Test
    @DisplayName("Shutdown 중 등록 거부")
    void trackOperation_shouldRejectDuringShutdown() {
      // Given
      tracker.awaitAllCompletion(Duration.ofMillis(100)); // Shutdown 시작

      // When & Then
      CompletableFuture<Void> future = new CompletableFuture<>();
      assertThatThrownBy(() -> tracker.trackOperation("ocid123", future))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Shutdown");
    }
  }

  @Nested
  @DisplayName("awaitAllCompletion() 테스트")
  class AwaitAllCompletionTest {

    @Test
    @DisplayName("빈 상태에서 즉시 성공")
    void awaitAllCompletion_shouldSucceedWhenEmpty() {
      // When
      boolean result = tracker.awaitAllCompletion(Duration.ofSeconds(1));

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("모든 작업 완료 대기")
    void awaitAllCompletion_shouldWaitForAll() {
      // Given
      CompletableFuture<Void> future1 = CompletableFuture.completedFuture(null);
      CompletableFuture<Void> future2 = CompletableFuture.completedFuture(null);
      tracker.trackOperation("ocid1", future1);
      tracker.trackOperation("ocid2", future2);

      // When
      boolean result = tracker.awaitAllCompletion(Duration.ofSeconds(5));

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("이미 Shutdown 중이면 false 반환")
    void awaitAllCompletion_shouldReturnFalseIfAlreadyShuttingDown() {
      // Given: 첫 번째 Shutdown 호출
      tracker.awaitAllCompletion(Duration.ofMillis(100));

      // When: 두 번째 Shutdown 호출
      boolean result = tracker.awaitAllCompletion(Duration.ofMillis(100));

      // Then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("getPendingOcids() 테스트")
  class GetPendingOcidsTest {

    @Test
    @DisplayName("로컬 pending 목록 조회")
    void getLocalPendingOcids_shouldReturnLocalList() {
      // Given
      tracker.trackOperation("ocid1", new CompletableFuture<>());
      tracker.trackOperation("ocid2", new CompletableFuture<>());

      // When
      var localPending = tracker.getLocalPendingOcids();

      // Then
      assertThat(localPending).containsExactlyInAnyOrder("ocid1", "ocid2");
    }

    @Test
    @DisplayName("전역 pending 목록 조회")
    void getGlobalPendingOcids_shouldReturnGlobalList() {
      // Given
      tracker.trackOperation("ocid1", new CompletableFuture<>());
      tracker.trackOperation("ocid2", new CompletableFuture<>());

      // When
      var globalPending = tracker.getGlobalPendingOcids();

      // Then
      assertThat(globalPending).containsExactlyInAnyOrder("ocid1", "ocid2");
    }
  }

  @Nested
  @DisplayName("isGloballyPending() 테스트")
  class IsGloballyPendingTest {

    @Test
    @DisplayName("전역에서 pending 상태 확인")
    void isGloballyPending_shouldReturnTrueWhenPending() {
      // Given
      tracker.trackOperation("ocid123", new CompletableFuture<>());

      // When
      boolean result = tracker.isGloballyPending("ocid123");

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("등록되지 않은 OCID는 false")
    void isGloballyPending_shouldReturnFalseWhenNotPending() {
      // When
      boolean result = tracker.isGloballyPending("unknown");

      // Then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("resetForTesting() 테스트")
  class ResetForTestingTest {

    @Test
    @DisplayName("테스트 리셋 - 모든 상태 초기화")
    void resetForTesting_shouldClearAll() {
      // Given
      tracker.trackOperation("ocid1", new CompletableFuture<>());
      tracker.awaitAllCompletion(Duration.ofMillis(100)); // Shutdown 시작

      // When
      tracker.resetForTesting();

      // Then
      assertThat(tracker.getLocalPendingCount()).isZero();
      assertThat(tracker.getGlobalPendingCount()).isZero();

      // Shutdown 해제되어 등록 가능
      tracker.trackOperation("ocid2", new CompletableFuture<>());
      assertThat(tracker.getLocalPendingCount()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("메트릭 테스트")
  class MetricsTest {

    @Test
    @DisplayName("메트릭 등록 확인")
    void metrics_shouldBeRegistered() {
      // Given
      tracker.trackOperation("ocid1", new CompletableFuture<>());
      tracker.trackOperation("ocid2", new CompletableFuture<>());

      // When
      int localCount = tracker.getLocalPendingCount();
      int globalCount = tracker.getGlobalPendingCount();

      // Then
      assertThat(localCount).isEqualTo(2);
      assertThat(globalCount).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("키 구조 테스트")
  class KeyStructureTest {

    @Test
    @DisplayName("Redis SET 키 - Hash Tag 패턴 확인")
    void redisKey_shouldUseHashTag() {
      // When
      tracker.trackOperation("ocid123", new CompletableFuture<>());

      // Then
      verify(redissonClient).getSet(RedisKey.PERSISTENCE_TRACKING.getKey());
    }
  }
}
