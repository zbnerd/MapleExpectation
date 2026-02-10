package maple.expectation.global.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

/**
 * IdempotencyGuard 단위 테스트 (#271 V5 Stateless Architecture)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Yellow (QA): SETNX 패턴 테스트
 *   <li>Purple (Auditor): 멱등성 보장 검증
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyGuard 단위 테스트")
class IdempotencyGuardTest {

  @Mock private RedissonClient redissonClient;

  @Mock private LogicExecutor executor;

  @Mock private RBucket<String> rBucket;

  private SimpleMeterRegistry meterRegistry;
  private IdempotencyGuard guard;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();

    // Redisson Mocks
    lenient().when(redissonClient.<String>getBucket(anyString())).thenReturn(rBucket);

    // LogicExecutor Mocks
    setupExecutorMocks();

    guard = new IdempotencyGuard(redissonClient, executor, meterRegistry);

    // Reflection으로 ttlHours 설정 (테스트용)
    try {
      var field = IdempotencyGuard.class.getDeclaredField("ttlHours");
      field.setAccessible(true);
      field.set(guard, 24);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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
  }

  @Nested
  @DisplayName("tryAcquire() 테스트")
  class TryAcquireTest {

    @Test
    @DisplayName("첫 번째 요청 - true 반환 (SETNX 성공)")
    void tryAcquire_shouldReturnTrueWhenFirstRequest() {
      // Given
      when(rBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);

      // When
      boolean result = guard.tryAcquire("expectation", "msg-123");

      // Then
      assertThat(result).isTrue();
      verify(rBucket).setIfAbsent("PROCESSING", Duration.ofHours(24));
    }

    @Test
    @DisplayName("중복 요청 - false 반환 (이미 처리 중)")
    void tryAcquire_shouldReturnFalseWhenAlreadyProcessing() {
      // Given
      when(rBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(false);
      when(rBucket.get()).thenReturn("PROCESSING");

      // When
      boolean result = guard.tryAcquire("expectation", "msg-123");

      // Then
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("완료된 요청 - false 반환 (이미 완료)")
    void tryAcquire_shouldReturnFalseWhenAlreadyCompleted() {
      // Given
      when(rBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(false);
      when(rBucket.get()).thenReturn("COMPLETED");

      // When
      boolean result = guard.tryAcquire("expectation", "msg-123");

      // Then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("markCompleted() 테스트")
  class MarkCompletedTest {

    @Test
    @DisplayName("정상 완료 마킹")
    void markCompleted_shouldSetStatusToCompleted() {
      // When
      guard.markCompleted("expectation", "msg-123");

      // Then
      verify(rBucket).set("COMPLETED", 24, TimeUnit.HOURS);
    }
  }

  @Nested
  @DisplayName("release() 테스트")
  class ReleaseTest {

    @Test
    @DisplayName("정상 릴리스 - 키 삭제 성공")
    void release_shouldDeleteKey() {
      // Given
      when(rBucket.delete()).thenReturn(true);

      // When
      guard.release("expectation", "msg-123");

      // Then
      verify(rBucket).delete();
    }

    @Test
    @DisplayName("키 없음 - 삭제 실패 (멱등성)")
    void release_shouldHandleKeyNotFound() {
      // Given
      when(rBucket.delete()).thenReturn(false);

      // When & Then: 예외 없이 수행
      guard.release("expectation", "msg-123");
    }
  }

  @Nested
  @DisplayName("getStatus() 테스트")
  class GetStatusTest {

    @Test
    @DisplayName("PROCESSING 상태 조회")
    void getStatus_shouldReturnProcessing() {
      // Given
      when(rBucket.get()).thenReturn("PROCESSING");

      // When
      String status = guard.getStatus("expectation", "msg-123");

      // Then
      assertThat(status).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("COMPLETED 상태 조회")
    void getStatus_shouldReturnCompleted() {
      // Given
      when(rBucket.get()).thenReturn("COMPLETED");

      // When
      String status = guard.getStatus("expectation", "msg-123");

      // Then
      assertThat(status).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("키 없음 - null 반환")
    void getStatus_shouldReturnNullWhenKeyNotExists() {
      // Given
      when(rBucket.get()).thenReturn(null);

      // When
      String status = guard.getStatus("expectation", "msg-123");

      // Then
      assertThat(status).isNull();
    }
  }

  @Nested
  @DisplayName("isCompleted() 테스트")
  class IsCompletedTest {

    @Test
    @DisplayName("완료 상태 - true")
    void isCompleted_shouldReturnTrueWhenCompleted() {
      // Given
      when(rBucket.get()).thenReturn("COMPLETED");

      // When
      boolean result = guard.isCompleted("expectation", "msg-123");

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("처리 중 상태 - false")
    void isCompleted_shouldReturnFalseWhenProcessing() {
      // Given
      when(rBucket.get()).thenReturn("PROCESSING");

      // When
      boolean result = guard.isCompleted("expectation", "msg-123");

      // Then
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("키 없음 - false")
    void isCompleted_shouldReturnFalseWhenKeyNotExists() {
      // Given
      when(rBucket.get()).thenReturn(null);

      // When
      boolean result = guard.isCompleted("expectation", "msg-123");

      // Then
      assertThat(result).isFalse();
    }
  }
}
