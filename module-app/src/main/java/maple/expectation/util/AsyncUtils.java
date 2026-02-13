package maple.expectation.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.ApiTimeoutException;
import maple.expectation.global.error.exception.InternalSystemException;
import maple.expectation.infrastructure.executor.TaskContext;

/**
 * CompletableFuture 비동기 실행 유틸리티
 *
 * <h3>목적</h3>
 *
 * <p>반복되는 CompletableFuture 예외 처리 패턴을 표준화합니다.
 *
 * <h3>주요 기능</h3>
 *
 * <ul>
 *   <li><b>CompletionException unwrap</b> - CompletableFuture 래핑 예외를 원본으로 변환
 *   <li><b>타임아웃 처리</b> - orTimeout()과 예외 변환을 결합한 편의 메서드
 *   <li><b>비동기 실행</b> - supplyAsync()와 예외 처리를 통합
 * </ul>
 *
 * <h3>사용 예시</h3>
 *
 * <pre>{@code
 * // 기본 사용: 타임아웃과 예외 변환
 * CompletableFuture<String> future = AsyncUtils.withTimeout(
 *     () -> nexonApiClient.getOcid(userIgn),
 *     10,
 *     TimeUnit.SECONDS,
 *     "NexonOcidAPI"
 * );
 *
 * // Executor 지정
 * CompletableFuture<String> future = AsyncUtils.executeAsync(
 *     () -> heavyOperation(data),
 *     executor,
 *     30,
 *     TimeUnit.SECONDS,
 *     "HeavyOperation"
 * );
 * }</pre>
 *
 * <h3>CLAUDE.md 준수</h3>
 *
 * <ul>
 *   <li>Section 4: DRY 원칙 - 14개 파일에서 반복되는 패턴을 유틸리티로 추출
 *   <li>Section 6: Utility 클래스 → private 생성자, static 메서드
 *   <li>Section 11: Exception Handling - ApiTimeoutException 사용으로 서킷브레이커 기록
 * </ul>
 *
 * @see ExceptionUtils
 * @see ApiTimeoutException
 */
@Slf4j
public final class AsyncUtils {

  private AsyncUtils() {
    // Utility class - private constructor
  }

  /**
   * CompletionException에서 원본 예외 추출
   *
   * <h4>처리 순서</h4>
   *
   * <ol>
   *   <li>CompletionException/ExecutionException → cause 반환
   *   <li>그 외 → 입력 그대로 반환
   * </ol>
   *
   * @param throwable unwrap할 예외
   * @return 원본 예외 (래퍼 예외가 아닌 경우 입력 그대로 반환)
   */
  public static Throwable unwrapCompletionException(Throwable throwable) {
    if (throwable instanceof CompletionException || throwable instanceof ExecutionException) {
      return throwable.getCause() != null ? throwable.getCause() : throwable;
    }
    return throwable;
  }

  /**
   * 타임아웃 적용과 예외 변환을 결합한 유틸리티
   *
   * <h4>기능</h4>
   *
   * <ul>
   *   <li>CompletableFuture.orTimeout() 적용
   *   <li>TimeoutException → ApiTimeoutException 변환 (서킷브레이커 기록용)
   *   <li>CompletionException unwrap 후 RuntimeException 재전파
   * </ul>
   *
   * <h4>예외 변환 규칙</h4>
   *
   * <ol>
   *   <li>TimeoutException → ApiTimeoutException(apiName, cause)
   *   <li>RuntimeException → 그대로 전파
   *   <li>그 외 → CompletionException으로 래핑
   * </ol>
   *
   * @param future 타임아웃을 적용할 Future
   * @param timeout 타임아웃 시간
   * @param unit 시간 단위
   * @param apiName API 이름 (ApiTimeoutException 메시지용)
   * @param <T> 결과 타입
   * @return 타임아웃과 예외 변환이 적용된 Future
   */
  public static <T> CompletableFuture<T> withTimeout(
      CompletableFuture<T> future, long timeout, TimeUnit unit, String apiName) {
    return future
        .orTimeout(timeout, unit)
        .exceptionally(
            e -> {
              Throwable cause = unwrapCompletionException(e);

              if (cause instanceof TimeoutException) {
                throw new ApiTimeoutException(apiName, cause);
              }

              // RuntimeException은 그대로 전파
              if (cause instanceof RuntimeException re) {
                throw re;
              }

              // 그 외 예외는 CompletionException으로 래핑
              throw new CompletionException(cause);
            });
  }

  /**
   * 비동기 실행 + 타임아웃 + 예외 변환을 통합한 유틸리티
   *
   * <h4>기능</h4>
   *
   * <ul>
   *   <li>CompletableFuture.supplyAsync()로 비동기 실행
   *   <li>지정된 Executor에서 실행 (ForkJoinPool.defaultExecutor() 사용 가능)
   *   <li>타임아웃 적용 및 예외 변환
   * </ul>
   *
   * <h4>사용 예시</h3>
   *
   * <pre>{@code
   * CompletableFuture<Result> future = AsyncUtils.executeAsync(
   *     () -> dataProvider.fetchData(key),
   *     executor,
   *     30,
   *     TimeUnit.SECONDS,
   *     "DataFetch"
   * );
   * }</pre>
   *
   * @param supplier 비동기로 실행할 작업
   * @param executor 실행할 Executor (null 시 ForkJoinPool.commonPool())
   * @param timeout 타임아웃 시간
   * @param unit 시간 단위
   * @param apiName API 이름 (ApiTimeoutException 메시지용)
   * @param <T> 결과 타입
   * @return 비동기 실행 + 타임아웃 + 예외 변환이 적용된 Future
   */
  public static <T> CompletableFuture<T> executeAsync(
      Supplier<T> supplier, Executor executor, long timeout, TimeUnit unit, String apiName) {
    CompletableFuture<T> future =
        executor != null
            ? CompletableFuture.supplyAsync(supplier, executor)
            : CompletableFuture.supplyAsync(supplier);

    return withTimeout(future, timeout, unit, apiName);
  }

  /**
   * Executor 없이 비동기 실행 (ForkJoinPool.commonPool() 사용)
   *
   * <h4>주의사항</h4>
   *
   * <p>I/O 작업에는 반드시 전용 Executor를 사용하세요. ForkJoinPool은 CPU 연산에 최적화되어 있습니다.
   *
   * @param supplier 비동기로 실행할 작업
   * @param timeout 타임아웃 시간
   * @param unit 시간 단위
   * @param apiName API 이름
   * @param <T> 결과 타입
   * @return 비동기 Future
   */
  public static <T> CompletableFuture<T> executeAsync(
      Supplier<T> supplier, long timeout, TimeUnit unit, String apiName) {
    return executeAsync(supplier, null, timeout, unit, apiName);
  }

  /**
   * exceptionally()에서 예외를 안전하게 처리하는 유틸리티
   *
   * <h4>기능</h4>
   *
   * <ul>
   *   <li>CompletionException unwrap
   *   <li>TimeoutException → ApiTimeoutException 변환
   *   <li>RuntimeException 전파
   *   <li>Checked Exception → CompletionException 래핑
   * </ul>
   *
   * <h4>사용 예시</h4>
   *
   * <pre>{@code
   * .exceptionally(e -> AsyncUtils.handleException(e, "MyAPI"))
   * }</pre>
   *
   * @param e 발생한 예외
   * @param apiName API 이름
   * @return 없음 (반드시 예외를 던져야 함)
   */
  public static <T> T handleException(Throwable e, String apiName) {
    Throwable cause = unwrapCompletionException(e);

    if (cause instanceof TimeoutException) {
      throw new ApiTimeoutException(apiName, cause);
    }

    if (cause instanceof RuntimeException re) {
      throw re;
    }

    throw new CompletionException(cause);
  }

  /**
   * ExceptionUtils와의 호환성을 위한 위임 메서드
   *
   * <p>비동기 래퍼 예외(CompletionException, ExecutionException)를 원본으로 unwrap합니다.
   *
   * @param throwable unwrap할 예외
   * @return 원본 예외
   * @see ExceptionUtils#unwrapAsyncException(Throwable)
   */
  public static Throwable unwrapAsyncException(Throwable throwable) {
    return ExceptionUtils.unwrapAsyncException(throwable);
  }

  /**
   * LogicExecutor 패턴을 사용하여 Callable을 비동기로 실행
   *
   * <h4>기능</h4>
   *
   * <ul>
   *   <li>지정된 Executor에서 Callable 비동기 실행
   *   <li>LogicExecutor 예외 처리 패턴 적용 (CLAUDE.md Section 12)
   *   <li>Checked Exception은 RuntimeException으로 변환하여 전파
   *   <li>TaskContext를 통한 구조화된 로그 및 메트릭 기록
   * </ul>
   *
   * <h4>예외 처리 규칙</h4>
   *
   * <ol>
   *   <li>Error → 즉시 전파 (OOM, StackOverflow 등)
   *   <li>RuntimeException → 그대로 전파
   *   <li>Checked Exception → InternalSystemException으로 래핑 후 전파
   *   <li>CompletionException → 원본 예외로 unwrap 후 처리
   * </ol>
   *
   * <h4>사용 예시</h4>
   *
   * <pre>{@code
   * CompletableFuture<String> future = AsyncUtils.executeAsync(
   *     () -> externalApi.fetchData(id),
   *     executor,
   *     TaskContext.of("ExternalAPI", "fetchData", "ID:" + id)
   * );
   *
   * String result = future.join(); // 예외 발생 시 RuntimeException으로 전파됨
   * }</pre>
   *
   * <h3>CLAUDE.md Section 12 준수</h3>
   *
   * <ul>
   *   <li>LogicExecutor 패턴: 예외 변환은 executeWithTranslation()과 동일
   *   <li>No try-catch: 내부적으로 Checked Exception을 RuntimeException으로 변환
   *   <li>TaskContext: 메트릭 카디널리티 통제
   * </ul>
   *
   * @param supplier 비동기로 실행할 작업 (Checked Exception 발생 가능)
   * @param executor 실행할 Executor (null 시 ForkJoinPool.commonPool())
   * @param context 작업 컨텍스트 (메트릭/로그용)
   * @param <T> 결과 타입
   * @return 비동기 실행 결과를 담은 CompletableFuture
   * @throws InternalSystemException Checked Exception 발생 시
   * @see TaskContext
   * @see maple.expectation.global.executor.LogicExecutor
   */
  public static <T> CompletableFuture<T> executeAsync(
      Callable<T> supplier, Executor executor, TaskContext context) {

    CompletableFuture<T> future =
        executor != null
            ? CompletableFuture.supplyAsync(
                () -> executeCallableWithExceptionTranslation(supplier, context), executor)
            : CompletableFuture.supplyAsync(
                () -> executeCallableWithExceptionTranslation(supplier, context));

    return future.exceptionally(
        e -> {
          Throwable unwrapped = unwrapCompletionException(e);

          // Error는 그대로 전파 (OOM, StackOverflow 등)
          if (unwrapped instanceof Error err) {
            throw err;
          }

          // RuntimeException은 그대로 전파 (BaseException 포함)
          if (unwrapped instanceof RuntimeException re) {
            throw re;
          }

          // 그 외 예외는 CompletionException으로 래핑
          throw new CompletionException(unwrapped);
        });
  }

  /**
   * Callable을 실행하고 Checked Exception을 RuntimeException으로 변환
   *
   * <h4>LogicExecutor 패턴 적용</h4>
   *
   * <p>CLAUDE.md Section 12의 executeWithTranslation() 패턴을 따라 Checked Exception을
   * InternalSystemException으로 변환합니다.
   *
   * <h4>예외 변환 규칙</h4>
   *
   * <ol>
   *   <li>Error → 즉시 전파
   *   <li>RuntimeException → 그대로 전파
   *   <li>Checked Exception → InternalSystemException(context.toTaskName(), cause)
   * </ol>
   *
   * @param supplier 실행할 Callable
   * @param context 작업 컨텍스트 (예외 변환 시 사용)
   * @param <T> 결과 타입
   * @return 실행 결과
   * @throws Error 치명적 시스템 오류 발생 시
   * @throws RuntimeException 비즈니스 예외 또는 변환된 시스템 예외
   */
  private static <T> T executeCallableWithExceptionTranslation(
      Callable<T> supplier, TaskContext context) {
    try {
      return supplier.call();
    } catch (Error e) {
      throw e; // Error는 즉시 전파 (OOM, StackOverflow 등)
    } catch (RuntimeException e) {
      throw e; // RuntimeException은 그대로 전파 (BaseException 포함)
    } catch (Throwable e) {
      // Checked Exception을 InternalSystemException으로 변환
      throw new InternalSystemException(context.toTaskName(), e);
    }
  }
}
