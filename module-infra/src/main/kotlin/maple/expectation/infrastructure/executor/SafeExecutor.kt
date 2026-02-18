package maple.expectation.infrastructure.executor

import maple.expectation.common.function.ThrowingSupplier

/**
 * Safe execution pattern interface.
 *
 * Provides fallback capabilities for error recovery.
 * Part of Interface Segregation Principle (ISP) compliance - clients only depend on methods they use.
 */
interface SafeExecutor {
    /**
     * Execute task with default value on exception.
     */
    fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T
}
