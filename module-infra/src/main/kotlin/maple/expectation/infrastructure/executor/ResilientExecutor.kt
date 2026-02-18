package maple.expectation.infrastructure.executor

import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator

/**
 * Resilient execution pattern interface.
 *
 * Provides advanced error handling with translation and fallback capabilities.
 * Part of Interface Segregation Principle (ISP) compliance - clients only depend on methods they use.
 */
interface ResilientExecutor {
    /**
     * Execute task with exception translation.
     */
    fun <T> executeWithTranslation(
        task: ThrowingSupplier<T>,
        customTranslator: ExceptionTranslator,
        context: TaskContext
    ): T

    /**
     * Execute task with fallback on exception (receives original exception).
     */
    fun <T> executeWithFallback(
        task: ThrowingSupplier<T>,
        fallback: (Throwable) -> T,
        context: TaskContext
    ): T

    /**
     * Execute task with translated exception recovery.
     */
    fun <T> executeOrCatch(
        task: ThrowingSupplier<T>,
        recovery: (Throwable) -> T,
        context: TaskContext
    ): T
}
