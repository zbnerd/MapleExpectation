package maple.expectation.infrastructure.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.global.error.exception.CharacterNotFoundException;
import maple.expectation.global.error.exception.DistributedLockException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.client.RedisException;
import org.redisson.client.RedisTimeoutException;

/**
 * ResilientLockStrategy 예외 필터링 테스트 (정책 검증)
 *
 * <p><b>테스트 목표</b>:
 *
 * <ul>
 *   <li>비즈니스 예외(ClientBaseException)는 MySQL fallback 없이 상위 전파
 *   <li>래핑된 비즈니스 예외(CompletionException 내부)도 fallback 없이 상위 전파
 *   <li>인프라 예외(Redis/CircuitBreaker)만 MySQL fallback 트리거
 *   <li>Unknown 예외(NPE 등)는 fallback 없이 상위 전파
 * </ul>
 *
 * <p><b>P0 수정</b>: executeWithLock() 기반 테스트로 전환 - task 실행 중 발생하는 비즈니스 예외 필터링 검증
 */
@ExtendWith(MockitoExtension.class)
class ResilientLockStrategyExceptionFilterTest {

  @Mock private LockStrategy redisLockStrategy;

  @Mock private MySqlNamedLockStrategy mysqlLockStrategy;

  @Mock private CircuitBreakerRegistry circuitBreakerRegistry;

  @Mock private CircuitBreaker circuitBreaker;

  @Mock private LogicExecutor executor;

  @Mock private LockFallbackMetrics fallbackMetrics;

  private ResilientLockStrategy resilientLockStrategy;

  private static final String TEST_KEY = "test-key";
  private static final long WAIT_TIME = 1000L;
  private static final long LEASE_TIME = 5000L;

  @BeforeEach
  void setUp() {
    when(circuitBreakerRegistry.circuitBreaker("redisLock")).thenReturn(circuitBreaker);
    lenient().when(circuitBreaker.getState()).thenReturn(CircuitBreaker.State.CLOSED);
    lenient().when(circuitBreaker.getName()).thenReturn("redisLock");

    resilientLockStrategy =
        new ResilientLockStrategy(
            redisLockStrategy,
            mysqlLockStrategy,
            circuitBreakerRegistry,
            executor,
            fallbackMetrics);
  }

  /** executor.executeWithFallback()를 passthrough로 설정 - task 실행 후 예외 발생 시 fallback 함수 호출 */
  @SuppressWarnings("unchecked")
  private void setupExecutorFallbackPassthrough() {
    when(executor.executeWithFallback(
            any(ThrowingSupplier.class), any(Function.class), any(TaskContext.class)))
        .thenAnswer(
            invocation -> {
              ThrowingSupplier<?> task = invocation.getArgument(0, ThrowingSupplier.class);
              Function<Throwable, ?> fallback = invocation.getArgument(1, Function.class);

              try {
                return task.get();
              } catch (Throwable t) {
                return fallback.apply(t);
              }
            });
  }

  /** CircuitBreaker passthrough: executeCheckedSupplier가 받은 supplier를 실제로 실행 */
  @SuppressWarnings("unchecked")
  private void setupCircuitBreakerPassthrough() throws Throwable {
    when(circuitBreaker.executeCheckedSupplier(any()))
        .thenAnswer(
            inv -> {
              var supplier =
                  inv.getArgument(0, io.github.resilience4j.core.functions.CheckedSupplier.class);
              return supplier.get();
            });
  }

  /**
   * Redis passthrough: redisLockStrategy.executeWithLock이 받은 task를 실제로 실행 Issue #130 핵심: task 내부에서
   * 발생하는 예외가 전파되는지 검증
   */
  @SuppressWarnings("unchecked")
  private void setupRedisExecuteWithLockPassthrough() throws Throwable {
    when(redisLockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any()))
        .thenAnswer(
            inv -> {
              ThrowingSupplier<?> task = inv.getArgument(3, ThrowingSupplier.class);
              return task.get(); // task 실행
            });
  }

  // ========================================
  // 인프라 예외 → MySQL Fallback 테스트
  // ========================================

  @Nested
  @DisplayName("인프라 예외 발생 시 MySQL Fallback (executeWithLock)")
  class InfrastructureExceptionFallbackTest {

    @Test
    @DisplayName("1. DistributedLockException 발생 시 MySQL fallback 실행")
    void shouldFallbackToMySql_WhenRedisThrowsDistributedLockException() throws Throwable {
      // given
      setupExecutorFallbackPassthrough();
      DistributedLockException lockException = new DistributedLockException("Redis lock timeout");

      when(circuitBreaker.executeCheckedSupplier(any())).thenThrow(lockException);
      when(mysqlLockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any()))
          .thenReturn("fallback-result");

      // when
      Object result =
          resilientLockStrategy.executeWithLock(TEST_KEY, WAIT_TIME, LEASE_TIME, () -> "success");

      // then
      assertThat(result).isEqualTo("fallback-result");
      verify(mysqlLockStrategy, times(1))
          .executeWithLock(eq(TEST_KEY), eq(WAIT_TIME), eq(LEASE_TIME), any());
    }

    @Test
    @DisplayName("2. CallNotPermittedException (CircuitBreaker OPEN) 발생 시 MySQL fallback 실행")
    void shouldFallbackToMySql_WhenCircuitBreakerOpen() throws Throwable {
      // given
      setupExecutorFallbackPassthrough();
      // Mock으로 CallNotPermittedException 생성 (factory method 대신)
      CallNotPermittedException cbException = mock(CallNotPermittedException.class);

      when(circuitBreaker.executeCheckedSupplier(any())).thenThrow(cbException);
      when(mysqlLockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any()))
          .thenReturn("fallback-result");

      // when
      Object result =
          resilientLockStrategy.executeWithLock(TEST_KEY, WAIT_TIME, LEASE_TIME, () -> "success");

      // then
      assertThat(result).isEqualTo("fallback-result");
      verify(mysqlLockStrategy, times(1))
          .executeWithLock(eq(TEST_KEY), eq(WAIT_TIME), eq(LEASE_TIME), any());
    }

    @Test
    @DisplayName("3. RedisException 발생 시 MySQL fallback 실행")
    void shouldFallbackToMySql_WhenRedisExceptionOccurs() throws Throwable {
      // given
      setupExecutorFallbackPassthrough();
      RedisException redisException = new RedisException("Redis connection failed");

      when(circuitBreaker.executeCheckedSupplier(any())).thenThrow(redisException);
      when(mysqlLockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any()))
          .thenReturn("fallback-result");

      // when
      Object result =
          resilientLockStrategy.executeWithLock(TEST_KEY, WAIT_TIME, LEASE_TIME, () -> "success");

      // then
      assertThat(result).isEqualTo("fallback-result");
      verify(mysqlLockStrategy, times(1))
          .executeWithLock(eq(TEST_KEY), eq(WAIT_TIME), eq(LEASE_TIME), any());
    }

    @Test
    @DisplayName("4. RedisTimeoutException 발생 시 MySQL fallback 실행")
    void shouldFallbackToMySql_WhenRedisTimeoutExceptionOccurs() throws Throwable {
      // given
      setupExecutorFallbackPassthrough();
      RedisTimeoutException timeoutException = new RedisTimeoutException("Redis timeout");

      when(circuitBreaker.executeCheckedSupplier(any())).thenThrow(timeoutException);
      when(mysqlLockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any()))
          .thenReturn("fallback-result");

      // when
      Object result =
          resilientLockStrategy.executeWithLock(TEST_KEY, WAIT_TIME, LEASE_TIME, () -> "success");

      // then
      assertThat(result).isEqualTo("fallback-result");
      verify(mysqlLockStrategy, times(1))
          .executeWithLock(eq(TEST_KEY), eq(WAIT_TIME), eq(LEASE_TIME), any());
    }
  }

  // ========================================
  // 비즈니스 예외 → Fallback 없이 상위 전파 테스트
  // ========================================

  @Nested
  @DisplayName("비즈니스 예외 발생 시 Fallback 없이 상위 전파 (executeWithLock)")
  class BusinessExceptionPropagationTest {

    @Test
    @DisplayName(
        "5. ClientBaseException(CharacterNotFoundException) 발생 시 MySQL fallback 미실행, 예외 상위 전파")
    void shouldPropagateBusinessException_WhenClientBaseExceptionThrown() throws Throwable {
      // given
      setupExecutorFallbackPassthrough();
      CharacterNotFoundException businessException = new CharacterNotFoundException("TestUser");

      when(circuitBreaker.executeCheckedSupplier(any())).thenThrow(businessException);

      // when & then
      assertThatThrownBy(
              () ->
                  resilientLockStrategy.executeWithLock(
                      TEST_KEY, WAIT_TIME, LEASE_TIME, () -> "success"))
          .isSameAs(businessException);

      // MySQL fallback이 호출되지 않았음을 검증
      verify(mysqlLockStrategy, never()).executeWithLock(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("6. [핵심] CompletionException으로 래핑된 비즈니스 예외도 fallback 없이 상위 전파")
    void shouldPropagateWrappedBusinessException_WhenCompletionExceptionWrapsClientBaseException()
        throws Throwable {
      // given
      setupExecutorFallbackPassthrough();
      CharacterNotFoundException businessException = new CharacterNotFoundException("TestUser");
      CompletionException wrappedException = new CompletionException(businessException);

      when(circuitBreaker.executeCheckedSupplier(any())).thenThrow(wrappedException);

      // when & then
      // unwrap 로직으로 인해 원본 ClientBaseException이 전파되어야 함
      assertThatThrownBy(
              () ->
                  resilientLockStrategy.executeWithLock(
                      TEST_KEY, WAIT_TIME, LEASE_TIME, () -> "success"))
          .isSameAs(businessException);

      // MySQL fallback이 호출되지 않았음을 검증
      verify(mysqlLockStrategy, never()).executeWithLock(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("7. 다중 래핑된 비즈니스 예외도 unwrap하여 상위 전파")
    void shouldPropagateMultiWrappedBusinessException() throws Throwable {
      // given
      setupExecutorFallbackPassthrough();
      CharacterNotFoundException businessException = new CharacterNotFoundException("TestUser");
      // CompletionException(CompletionException(businessException))
      CompletionException innerWrapper = new CompletionException(businessException);
      CompletionException outerWrapper = new CompletionException(innerWrapper);

      when(circuitBreaker.executeCheckedSupplier(any())).thenThrow(outerWrapper);

      // when & then
      assertThatThrownBy(
              () ->
                  resilientLockStrategy.executeWithLock(
                      TEST_KEY, WAIT_TIME, LEASE_TIME, () -> "success"))
          .isSameAs(businessException);

      verify(mysqlLockStrategy, never()).executeWithLock(anyString(), anyLong(), anyLong(), any());
    }
  }

  // ========================================
  // Unknown 예외 → Fallback 없이 상위 전파 (보수 정책)
  // ========================================

  @Nested
  @DisplayName("Unknown 예외 발생 시 Fallback 없이 상위 전파 (버그 조기 발견)")
  class UnknownExceptionPropagationTest {

    @Test
    @DisplayName("8. NullPointerException 발생 시 MySQL fallback 미실행, 예외 상위 전파")
    void shouldPropagateUnknownException_WhenNPEOccurs() throws Throwable {
      // given
      setupExecutorFallbackPassthrough();
      NullPointerException npe = new NullPointerException("Unexpected null");

      when(circuitBreaker.executeCheckedSupplier(any())).thenThrow(npe);

      // when & then
      assertThatThrownBy(
              () ->
                  resilientLockStrategy.executeWithLock(
                      TEST_KEY, WAIT_TIME, LEASE_TIME, () -> "success"))
          .isSameAs(npe);

      // MySQL fallback이 호출되지 않았음을 검증 (보수 정책: 버그 조기 발견)
      verify(mysqlLockStrategy, never()).executeWithLock(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("9. IllegalArgumentException 발생 시 MySQL fallback 미실행, 예외 상위 전파")
    void shouldPropagateUnknownException_WhenIllegalArgumentExceptionOccurs() throws Throwable {
      // given
      setupExecutorFallbackPassthrough();
      IllegalArgumentException iae = new IllegalArgumentException("Invalid argument");

      when(circuitBreaker.executeCheckedSupplier(any())).thenThrow(iae);

      // when & then
      assertThatThrownBy(
              () ->
                  resilientLockStrategy.executeWithLock(
                      TEST_KEY, WAIT_TIME, LEASE_TIME, () -> "success"))
          .isSameAs(iae);

      verify(mysqlLockStrategy, never()).executeWithLock(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("10. RuntimeException (일반) 발생 시 MySQL fallback 미실행, 예외 상위 전파")
    void shouldPropagateUnknownException_WhenGenericRuntimeExceptionOccurs() throws Throwable {
      // given
      setupExecutorFallbackPassthrough();
      RuntimeException runtimeException = new RuntimeException("Generic runtime error");

      when(circuitBreaker.executeCheckedSupplier(any())).thenThrow(runtimeException);

      // when & then
      assertThatThrownBy(
              () ->
                  resilientLockStrategy.executeWithLock(
                      TEST_KEY, WAIT_TIME, LEASE_TIME, () -> "success"))
          .isSameAs(runtimeException);

      verify(mysqlLockStrategy, never()).executeWithLock(anyString(), anyLong(), anyLong(), any());
    }
  }

  // ========================================
  // Task 내부 비즈니스 예외 전파 테스트 (P0 핵심 시나리오)
  // Issue #130: "task 예외가 Redis 장애로 오인되어 fallback 되는 문제" 검증
  // ========================================

  @Nested
  @DisplayName("Task 실행 중 비즈니스 예외 발생 시 상위 전파 (Issue #130 핵심)")
  class TaskBusinessExceptionTest {

    @Test
    @DisplayName("11. [핵심] task에서 CharacterNotFoundException 발생 시 MySQL fallback 미실행")
    void shouldPropagateTaskBusinessException_WhenTaskThrowsClientBaseException() throws Throwable {
      // given
      // 실경로: executor → CB.executeCheckedSupplier(supplier.get()) →
      // redis.executeWithLock(task.get()) → task throws
      setupExecutorFallbackPassthrough();
      setupCircuitBreakerPassthrough();
      setupRedisExecuteWithLockPassthrough();

      CharacterNotFoundException business = new CharacterNotFoundException("TestUser");

      // when & then
      // task가 실제로 실행되어 비즈니스 예외를 던지고, handleFallback에서 fallback 없이 전파
      assertThatThrownBy(
              () ->
                  resilientLockStrategy.executeWithLock(
                      TEST_KEY,
                      WAIT_TIME,
                      LEASE_TIME,
                      () -> {
                        throw business;
                      }))
          .isSameAs(business);

      // MySQL fallback이 호출되지 않았음을 검증
      verify(mysqlLockStrategy, never()).executeWithLock(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("12. [핵심] task에서 CompletionException으로 래핑된 비즈니스 예외 발생 시 unwrap 후 상위 전파")
    void shouldUnwrapAndPropagateTaskBusinessException_WhenTaskThrowsWrappedClientBaseException()
        throws Throwable {
      // given
      // 실경로: executor → CB → redis → task throws CompletionException(business) → unwrap → 전파
      setupExecutorFallbackPassthrough();
      setupCircuitBreakerPassthrough();
      setupRedisExecuteWithLockPassthrough();

      CharacterNotFoundException business = new CharacterNotFoundException("TestUser");

      // when & then
      // task가 CompletionException으로 래핑된 비즈니스 예외를 던지고, unwrap 후 원본 전파
      assertThatThrownBy(
              () ->
                  resilientLockStrategy.executeWithLock(
                      TEST_KEY,
                      WAIT_TIME,
                      LEASE_TIME,
                      () -> {
                        throw new CompletionException(business);
                      }))
          .isSameAs(business);

      // MySQL fallback이 호출되지 않았음을 검증
      verify(mysqlLockStrategy, never()).executeWithLock(anyString(), anyLong(), anyLong(), any());
    }
  }
}
