package maple.expectation.global.executor;

import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import java.util.function.Function;

/**
 * 예외 처리 패턴과 관측성(Observability)을 추상화한 실행기
 * * <p>TaskContext를 통해 메트릭 카디널리티를 제어하며, 6가지 표준 예외 처리 패턴을 제공합니다.
 */
public interface LogicExecutor {

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
     * [패턴 5] 예외 발생 시 복구 로직 실행
     *
     * @param <T> 작업 결과 타입
     * @param task 실행할 작업
     * @param recovery 예외 발생 시 실행할 복구 함수
     * @param context 작업 컨텍스트
     * @return 작업 결과 또는 복구값
     */
    <T> T executeWithRecovery(
            ThrowingSupplier<T> task,
            Function<Throwable, T> recovery,
            TaskContext context
    );

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
    <T> T executeWithFinally(
            ThrowingSupplier<T> task,
            Runnable finallyBlock,
            TaskContext context
    );

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
            ThrowingSupplier<T> task,
            ExceptionTranslator translator,
            TaskContext context
    );

    // --- Backward Compatibility (하위 호환성 유지용 오버로딩) ---

    default <T> T execute(ThrowingSupplier<T> task, String taskName) {
        return execute(task, TaskContext.of("Legacy", taskName));
    }

    default void executeVoid(ThrowingRunnable task, String taskName) {
        executeVoid(task, TaskContext.of("Legacy", taskName));
    }
}