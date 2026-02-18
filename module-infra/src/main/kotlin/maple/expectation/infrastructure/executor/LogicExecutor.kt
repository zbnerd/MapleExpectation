package maple.expectation.infrastructure.executor

import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator

/**
 * Unified execution interface combining all execution patterns.
 *
 * Composes BasicExecutor, SafeExecutor, and ResilientExecutor to provide full capability.
 * Supports Interface Segregation Principle (ISP) by allowing clients to depend only on needed interfaces.
 *
 * TaskContext controls metric cardinality and provides 6 standard exception handling patterns.
 */
interface LogicExecutor : BasicExecutor, SafeExecutor, ResilientExecutor {

    // --- Documentation for inherited methods ---

    /**
     * [패턴 1, 2] 예외를 RuntimeException으로 변환하여 전파
     */
    override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T

    /**
     * [패턴 3, 4] 예외 발생 시 기본값 반환
     */
    override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T

    /**
     * [패턴 6] 특정 예외를 도메인 예외로 변환 (Translator 패턴)
     */
    override fun <T> executeWithTranslation(
        task: ThrowingSupplier<T>,
        customTranslator: ExceptionTranslator,
        context: TaskContext
    ): T

    /**
     * [패턴 8] 예외 발생 시 **원본 예외**로 Fallback 실행
     *
     * {@link #executeOrCatch}과의 차이:
     * - {@code executeWithFallback}: **원본** 예외가 그대로 fallback에 전달됨 (번역 없음)
     * - {@code executeOrCatch}: 기본 {@link ExceptionTranslator}로 **번역된** 예외가 recovery에 전달됨
     */
    override fun <T> executeWithFallback(
        task: ThrowingSupplier<T>,
        fallback: (Throwable) -> T,
        context: TaskContext
    ): T

    /**
     * [패턴 5] 예외 발생 시 **번역된 예외**로 복구 로직 실행
     *
     * {@link #executeWithFallback}과의 차이:
     * - {@code executeOrCatch}: 기본 {@link ExceptionTranslator}로 **번역된** 예외가 recovery에 전달됨
     * - {@code executeWithFallback}: **원본** 예외가 그대로 fallback에 전달됨
     */
    override fun <T> executeOrCatch(
        task: ThrowingSupplier<T>,
        recovery: (Throwable) -> T,
        context: TaskContext
    ): T

    /**
     * [패턴 1] void 작업 실행
     */
    override fun executeVoid(task: ThrowingRunnable, context: TaskContext)

    /**
     * [패턴 1] finally 블록 명시적 지정
     */
    override fun <T> executeWithFinally(
        task: ThrowingSupplier<T>,
        finallyBlock: Runnable,
        context: TaskContext
    ): T

    // --- Backward Compatibility (하위 호환성 유지용 오버로딩) ---

    /**
     * Legacy overload with taskName string
     */
    fun <T> execute(task: ThrowingSupplier<T>, taskName: String): T =
        execute(task, TaskContext.of("Legacy", taskName))

    /**
     * Legacy overload with taskName string
     */
    fun executeVoid(task: ThrowingRunnable, taskName: String) {
        executeVoid(task, TaskContext.of("Legacy", taskName))
    }
}
