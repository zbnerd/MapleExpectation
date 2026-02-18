package maple.expectation.infrastructure.executor

import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.policy.ExecutionPipeline
import maple.expectation.infrastructure.executor.policy.FinallyPolicy
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ExecutionPipeline 기반 LogicExecutor 구현체
 *
 * PRD v4 섹션 13.1 / 14 준수:
 * - **Pipeline executeRaw() 호출**: 모든 메서드가 ExecutionPipeline을 통해 실행
 * - **Error 즉시 rethrow**: VirtualMachineError 등은 번역 없이 전파
 * - **SG1 적용**: executeOrCatch는 execute() 재사용 금지, executeRaw() 직접 호출
 * - **호출부 0 수정**: 기존 메서드 시그니처 유지, 내부 구현만 교체
 */
@Component
class DefaultLogicExecutor(
    private val pipeline: ExecutionPipeline,
    private val translator: ExceptionTranslator
) : LogicExecutor {

    private val log = LoggerFactory.getLogger(DefaultLogicExecutor::class.java)

    companion object {
        private const val UNEXPECTED_TRANSLATOR_FAILURE = "Translator failed with unexpected Throwable"
    }

    override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T {
        requireNotNull(task) { "task must not be null" }
        requireNotNull(context) { "context must not be null" }

        return try {
            pipeline.executeRaw(task, context)
        } catch (e: Error) {
            throw e
        } catch (t: Throwable) {
            val primary = translatePrimary(t, context)
            throwAsUnchecked(primary)
            unreachable()
        }
    }

    override fun <T> executeOrCatch(
        task: ThrowingSupplier<T>,
        recovery: (Throwable) -> T,
        context: TaskContext
    ): T {
        requireNotNull(task) { "task must not be null" }
        requireNotNull(recovery) { "recovery must not be null" }
        requireNotNull(context) { "context must not be null" }

        return try {
            // SG1: execute() 재사용 금지, executeRaw 직접 호출
            pipeline.executeRaw(task, context)
        } catch (e: Error) {
            throw e
        } catch (t: Throwable) {
            // 호환성: 기존에는 execute() 경유로 "번역된 예외"가 recovery에 전달되었음.
            val forRecovery = translateForRecovery(t, context)
            return recovery(forRecovery)
        }
    }

    override fun <T> executeOrDefault(
        task: ThrowingSupplier<T>,
        defaultValue: T,
        context: TaskContext
    ): T {
        return executeOrCatch(task, { defaultValue }, context)
    }

    override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
        requireNotNull(task) { "task must not be null" }
        execute(
            task = {
                task.run()
                null
            },
            context = context
        )
    }

    override fun <T> executeWithFinally(
        task: ThrowingSupplier<T>,
        finallyBlock: Runnable,
        context: TaskContext
    ): T {
        requireNotNull(task) { "task must not be null" }
        requireNotNull(finallyBlock) { "finallyBlock must not be null" }
        requireNotNull(context) { "context must not be null" }

        // 목적:
        // 1) Finally를 정책(after)로 모델링하여 PRD v4 unwind 규약(suppressed 포함)에 합류
        // 2) 다만 BEFORE 단계에서 PROPAGATE로 터져 FinallyPolicy가 entered 못한 케이스까지 "정확히 1회" 실행 보장
        val ran = AtomicBoolean(false)

        val onceFinally = Runnable {
            if (ran.compareAndSet(false, true)) {
                finallyBlock.run()
            }
        }

        val withFinally = pipeline.withAdditionalPolicies(listOf(FinallyPolicy(onceFinally)))

        // 방어: withAdditionalPolicies가 원본을 mutate하는 구현이면, 정책이 호출마다 누적될 수 있다.
        if (withFinally == pipeline) {
            log.warn(
                "[DefaultLogicExecutor] ExecutionPipeline.withAdditionalPolicies returned same instance. " +
                    "If pipeline is mutable, FinallyPolicy may accumulate across calls."
            )
        }

        return try {
            withFinally.executeRaw(task, context)
        } catch (e: Error) {
            // Error도 "가능하면 정리"는 시도하되, Error를 덮어쓰지는 않는다.
            runCleanupSuppressing(e, onceFinally)
            throw e
        } catch (t: Throwable) {
            val primary = translatePrimary(t, context)
            runCleanupSuppressing(primary, onceFinally)
            throwAsUnchecked(primary)
            unreachable()
        }
    }

    override fun <T> executeWithTranslation(
        task: ThrowingSupplier<T>,
        customTranslator: ExceptionTranslator,
        context: TaskContext
    ): T {
        requireNotNull(task) { "task must not be null" }
        requireNotNull(customTranslator) { "customTranslator must not be null" }
        requireNotNull(context) { "context must not be null" }

        return try {
            pipeline.executeRaw(task, context)
        } catch (e: Error) {
            throw e
        } catch (t: Throwable) {
            val primary = translateSafe(customTranslator, t, context)
            throwAsUnchecked(primary)
            unreachable()
        }
    }

    override fun <T> executeWithFallback(
        task: ThrowingSupplier<T>,
        fallback: (Throwable) -> T,
        context: TaskContext
    ): T {
        requireNotNull(task) { "task must not be null" }
        requireNotNull(fallback) { "fallback must not be null" }
        requireNotNull(context) { "context must not be null" }

        return try {
            pipeline.executeRaw(task, context)
        } catch (e: Error) {
            throw e
        } catch (t: Throwable) {
            return fallback(t)
        }
    }

    // ========================================
    // Private Helpers
    // ========================================

    /**
     * 임의의 translator를 안전하게 호출한다 (executeWithTranslation 전용)
     * translatePrimary와 동일한 안전 가드를 적용하되, 주입된 기본 translator 대신 커스텀 translator를 사용한다.
     */
    private fun translateSafe(
        customTranslator: ExceptionTranslator,
        t: Throwable,
        context: TaskContext
    ): Throwable {
        return try {
            customTranslator.translate(t, context)
        } catch (ex: RuntimeException) {
            ex
        } catch (e: Error) {
            e
        } catch (unexpected: Throwable) {
            IllegalStateException(UNEXPECTED_TRANSLATOR_FAILURE, unexpected)
        }
    }

    /**
     * translator를 통해 "던질 primary"를 만든다.
     * - 정상: RuntimeException 반환
     * - translator가 RuntimeException으로 실패: 그 예외 자체를 primary로 삼는다.
     * - translator가 Error로 실패: Error를 primary로 삼는다.
     * - 계약 위반(Throwable): IllegalStateException으로 래핑하여 primary로 삼는다.
     */
    private fun translatePrimary(t: Throwable, context: TaskContext): Throwable {
        return try {
            translator.translate(t, context)
        } catch (ex: RuntimeException) {
            ex
        } catch (e: Error) {
            e
        } catch (unexpected: Throwable) {
            IllegalStateException(UNEXPECTED_TRANSLATOR_FAILURE, unexpected)
        }
    }

    /**
     * recovery에 넘길 throwable을 만든다.
     * - 기존 동작 호환성: execute()를 경유했을 때 recovery는 "번역된 RuntimeException"을 받았다.
     * - translator가 RuntimeException으로 실패하면: 그 예외 자체를 recovery에 전달한다.
     * - translator가 Error로 실패하면: Error는 복구 대상이 아니므로 전파한다.
     * - 계약 위반(Throwable): IllegalStateException으로 감싸 recovery에 전달한다.
     */
    private fun translateForRecovery(t: Throwable, context: TaskContext): Throwable {
        return try {
            translator.translate(t, context)
        } catch (ex: RuntimeException) {
            ex
        } catch (e: Error) {
            throw e
        } catch (unexpected: Throwable) {
            IllegalStateException(UNEXPECTED_TRANSLATOR_FAILURE, unexpected)
        }
    }

    /**
     * 정리 작업을 1회만 실행하고, 정리 중 예외가 나와도 primary를 덮지 않고 suppressed로만 합류시킨다.
     */
    private fun runCleanupSuppressing(primary: Throwable, onceFinally: Runnable) {
        try {
            onceFinally.run()
        } catch (cleanupEx: Throwable) {
            safeAddSuppressed(primary, cleanupEx)
        }
    }

    private fun safeAddSuppressed(primary: Throwable?, suppressed: Throwable?) {
        if (primary == null || suppressed == null) return
        if (primary === suppressed) return
        try {
            primary.addSuppressed(suppressed)
        } catch (ignore: Exception) {
            // suppressed 추가 실패는 실행 흐름을 깨지 않는다 (Error는 전파)
        }
    }

    private fun throwAsUnchecked(t: Throwable) {
        when (t) {
            is Error -> throw t
            is RuntimeException -> throw t
            else -> throw IllegalStateException("Unexpected checked throwable", t)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> unreachable(): T = null as T
}
