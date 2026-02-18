package maple.expectation.infrastructure.executor

import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.function.ThrowingRunnable

/**
 * Basic execution pattern interface.
 *
 * Provides core execution methods without fallback or translation capabilities.
 * Part of Interface Segregation Principle (ISP) compliance - clients only depend on methods they use.
 */
interface BasicExecutor {
    /**
     * Execute task with exception propagation.
     */
    fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T

    /**
     * Execute void task with exception propagation.
     */
    fun executeVoid(task: ThrowingRunnable, context: TaskContext)

    /**
     * Execute task with explicit finally block.
     */
    fun <T> executeWithFinally(task: ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T
}
