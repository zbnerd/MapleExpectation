package maple.expectation.global.executor;

import java.util.function.Function;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.global.executor.strategy.ExceptionTranslator;

/**
 * Unified execution interface combining all execution patterns.
 *
 * <p>Composes BasicExecutor, SafeExecutor, and ResilientExecutor to provide full capability.
 * Supports Interface Segregation Principle (ISP) by allowing clients to depend only on needed
 * interfaces.
 *
 * <p>TaskContext controls metric cardinality and provides 6 standard exception handling patterns.
 */
public interface LogicExecutor extends BasicExecutor, SafeExecutor, ResilientExecutor {

  // --- Documentation for inherited methods ---

  /**
   * [패턴 1, 2] 예외를 RuntimeException으로 변환하여 전파
   *
   * @param <T> 작업 결과 타입
   * @param task 실행할 작업
   * @param context 작업 컨텍스트 (메트릭/로그용)
   * @return 작업 결과
   */
  <T> T execute(ThrowingSupplier<T> task, TaskContext context);

  /**
   * [패턴 3, 4] 예외 발생 시 기본값 반환
   *
   * @param <T> 작업 결과 타입
   * @param task 실행할 작업
   * @param defaultValue 예외 발생 시 반환할 기본값
   * @param context 작업 컨텍스트
   * @return 작업 결과 또는 기본값
   */
  <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, TaskContext context);

  /**
   * [패턴 1] void 작업 실행
   *
   * @param task 실행할 작업
   * @param context 작업 컨텍스트
   */
  void executeVoid(ThrowingRunnable task, TaskContext context);

  /**
   * [패턴 1] finally 블록 명시적 지정
   *
   * @param <T> 작업 결과 타입
   * @param task 실행할 작업
   * @param finallyBlock 반드시 실행할 정리 작업
   * @param context 작업 컨텍스트
   * @return 작업 결과
   */
  <T> T executeWithFinally(ThrowingSupplier<T> task, Runnable finallyBlock, TaskContext context);

  /**
   * [패턴 6] 특정 예외를 도메인 예외로 변환 (Translator 패턴)
   *
   * @param <T> 작업 결과 타입
   * @param task 실행할 작업
   * @param translator 예외 변환 전략
   * @param context 작업 컨텍스트
   * @return 작업 결과
   */
  <T> T executeWithTranslation(
      ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context);

  /**
   * [패턴 8] 예외 발생 시 <b>원본 예외</b>로 Fallback 실행
   *
   * <p>{@link #executeOrCatch}과의 차이:
   *
   * <ul>
   *   <li>{@code executeWithFallback}: <b>원본</b> 예외가 그대로 fallback에 전달됨 (번역 없음)
   *   <li>{@code executeOrCatch}: 기본 {@link ExceptionTranslator}로 <b>번역된</b> 예외가 recovery에 전달됨
   * </ul>
   *
   * @param <T> 작업 결과 타입
   * @param task 실행할 작업
   * @param fallback 원본 예외를 받아 복구값을 반환하는 함수
   * @param context 작업 컨텍스트
   * @return 작업 결과 또는 Fallback 결과
   */
  <T> T executeWithFallback(
      ThrowingSupplier<T> task, Function<Throwable, T> fallback, TaskContext context);

  /**
   * [패턴 5] 예외 발생 시 <b>번역된 예외</b>로 복구 로직 실행
   *
   * <p>{@link #executeWithFallback}과의 차이:
   *
   * <ul>
   *   <li>{@code executeOrCatch}: 기본 {@link ExceptionTranslator}로 <b>번역된</b> 예외가 recovery에 전달됨
   *   <li>{@code executeWithFallback}: <b>원본</b> 예외가 그대로 fallback에 전달됨
   * </ul>
   *
   * @param <T> 작업 결과 타입
   * @param task 실행할 작업
   * @param recovery 번역된 예외를 받아 복구값을 반환하는 함수
   * @param context 작업 컨텍스트
   * @return 작업 결과 또는 복구값
   */
  <T> T executeOrCatch(
      ThrowingSupplier<T> task, Function<Throwable, T> recovery, TaskContext context);

  // --- Backward Compatibility (하위 호환성 유지용 오버로딩) ---

  default <T> T execute(ThrowingSupplier<T> task, String taskName) {
    return execute(task, TaskContext.of("Legacy", taskName));
  }

  default void executeVoid(ThrowingRunnable task, String taskName) {
    executeVoid(task, TaskContext.of("Legacy", taskName));
  }
}
