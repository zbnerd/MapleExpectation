package maple.expectation.infrastructure.executor

import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.function.CheckedRunnable
import maple.expectation.infrastructure.executor.function.CheckedSupplier
import maple.expectation.infrastructure.executor.policy.ExecutionPipeline
import maple.expectation.util.InterruptUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * CheckedLogicExecutor의 기본 구현체
 *
 * ExecutionPipeline 기반으로 checked 예외를 처리하며, try-catch 없이 예외 변환을 템플릿 내부로 중앙화합니다.
 *
 * ## 핵심 계약 (ADR)
 * - **Error 즉시 전파**: VirtualMachineError 등은 매핑/복구 없이 즉시 throw
 * - **RuntimeException 통과**: 이미 unchecked이므로 그대로 throw
 * - **Exception → mapper 변환**: checked 예외만 mapper로 RuntimeException 변환
 * - **mapper 계약 방어**: null 반환, 계약 위반 시 IllegalStateException
 * - **suppressed 이관**: Exception→RuntimeException 변환 시 suppressed 복사
 * - **인터럽트 플래그 복원**: InterruptedException 발생 시 Thread.currentThread().interrupt()
 */
@Component
class DefaultCheckedLogicExecutor(
    private val pipeline: ExecutionPipeline
) : CheckedLogicExecutor {

    private val log = LoggerFactory.getLogger(DefaultCheckedLogicExecutor::class.java)

    // ========================================
    // Level 2: throws 전파 (상위에서 처리)
    // ========================================

    @Throws(Exception::class)
    override fun <T> execute(task: CheckedSupplier<T>, context: TaskContext): T {
        requireNotNull(task) { "task must not be null" }
        requireNotNull(context) { "context must not be null" }

        return try {
            // 명시적 람다로 오버로드 추론 문제 방지
            pipeline.executeRaw(ThrowingSupplier<T> { task.get() }, context)
        } catch (e: Error) {
            throw e
        } catch (re: RuntimeException) {
            // RuntimeException도 cause chain에 InterruptedException이 있으면 복원
            InterruptUtils.restoreInterruptIfNeeded(re)
            throw re
        } catch (e: Exception) {
            InterruptUtils.restoreInterruptIfNeeded(e)
            throw e
        } catch (t: Throwable) {
            throw IllegalStateException(
                "Unexpected Throwable (not Error/Exception): ${t.javaClass.name}",
                t
            )
        }
    }

    // ========================================
    // Level 1: checked → runtime 변환 (try-catch 제거)
    // ========================================

    override fun <T> executeUnchecked(
        task: CheckedSupplier<T>,
        context: TaskContext,
        mapper: java.util.function.Function<Exception, RuntimeException>
    ): T {
        requireNotNull(task) { "task must not be null" }
        requireNotNull(context) { "context must not be null" }
        requireNotNull(mapper) { "mapper must not be null" }

        return try {
            // 명시적 람다로 오버로드 추론 문제 방지
            pipeline.executeRaw(ThrowingSupplier<T> { task.get() }, context)
        } catch (t: Throwable) {
            // Error → 즉시 throw (mapper 미호출)
            if (t is Error) throw t

            // RuntimeException → 그대로 throw (이미 unchecked)
            // cause chain에 InterruptedException이 있으면 복원
            if (t is RuntimeException) {
                InterruptUtils.restoreInterruptIfNeeded(t)
                throw t
            }

            // Exception이 아닌 Throwable → 계약 위반 (예: custom Throwable subclass)
            if (t !is Exception) {
                throw IllegalStateException(
                    "Task threw non-Exception Throwable: ${t.javaClass.name}",
                    t
                )
            }

            // Exception → mapper로 변환
            InterruptUtils.restoreInterruptIfNeeded(t)
            throw applyMapper(mapper, t)
        }
    }

    // ========================================
    // Level 1 + finally: 자원 해제 보장
    // ========================================

    /**
     * ADR: finalizer 중복 등록 금지
     *
     * 이 메서드는 자체 try-finally로 finalizer를 1회 실행합니다.
     * 동일한 finalizer를 FinallyPolicy에 중복 등록하면 2회 실행되어 예기치 않은 동작이 발생할 수 있습니다.
     */
    override fun <T> executeWithFinallyUnchecked(
        task: CheckedSupplier<T>,
        finalizer: CheckedRunnable,
        context: TaskContext,
        mapper: java.util.function.Function<Exception, RuntimeException>
    ): T {
        requireNotNull(task) { "task must not be null" }
        requireNotNull(finalizer) { "finalizer must not be null" }
        requireNotNull(context) { "context must not be null" }
        requireNotNull(mapper) { "mapper must not be null" }

        var result: T? = null
        var primary: Throwable? = null

        try {
            // 명시적 람다로 오버로드 추론 문제 방지
            @Suppress("UNCHECKED_CAST")
            result = pipeline.executeRaw(
                ThrowingSupplier<Any> { task.get() } as ThrowingSupplier<T>,
                context
            )
        } catch (t: Throwable) {
            primary = t
        } finally {
            try {
                finalizer.run()
            } catch (ft: Throwable) {
                // finalizer Error는 즉시 throw, task primary는 suppressed로 보존 (P1-6)
                if (ft is Error) {
                    if (primary != null) {
                        safeAddSuppressed(ft, primary)
                    }
                    throw ft
                }

                if (primary == null) {
                    primary = ft
                } else {
                    safeAddSuppressed(primary, ft)
                }
            }
        }

        // 정상 완료
        if (primary == null) {
            @Suppress("UNCHECKED_CAST")
            return result as T
        }

        // Error → 즉시 throw
        if (primary is Error) throw primary

        // RuntimeException → 그대로 throw
        // cause chain에 InterruptedException이 있으면 복원
        if (primary is RuntimeException) {
            InterruptUtils.restoreInterruptIfNeeded(primary)
            throw primary
        }

        // Exception → mapper 변환 + suppressed 이관
        if (primary is Exception) {
            InterruptUtils.restoreInterruptIfNeeded(primary)
            val mapped = applyMapper(mapper, primary)
            copySuppressed(primary, mapped)
            throw mapped
        }

        // Throwable (비-Exception) → 계약 위반
        throw IllegalStateException(
            "Task threw non-Exception Throwable: ${primary.javaClass.name}",
            primary
        )
    }

    // ========================================
    // Private Helpers
    // ========================================

    /**
     * mapper 계약 방어 (단일 진실원)
     *
     * mapper가 계약을 위반하면 IllegalStateException을 throw합니다:
     * - null 반환 → IllegalStateException
     * - Error throw → 그대로 throw
     * - RuntimeException throw → 그대로 throw
     * - 기타 Throwable throw → IllegalStateException
     */
    private fun applyMapper(
        mapper: java.util.function.Function<Exception, RuntimeException>,
        ex: Exception
    ): RuntimeException {
        return try {
            val mapped = mapper.apply(ex) ?: throw IllegalStateException(
                "Exception mapper returned null for: ${ex.javaClass.name}"
            )
            mapped
        } catch (e: Error) {
            throw e
        } catch (re: RuntimeException) {
            throw re
        } catch (mt: Throwable) {
            throw IllegalStateException(
                "Exception mapper violated contract (threw non-RuntimeException): ${mt.javaClass.name}",
                mt
            )
        }
    }

    /**
     * suppressed 예외 이관
     *
     * from에 누적된 suppressed 예외들을 to로 복사합니다.
     * cleanup 실패 정보가 유실되지 않도록 합니다.
     */
    private fun copySuppressed(from: Throwable, to: Throwable) {
        for (s in from.suppressed) {
            safeAddSuppressed(to, s)
        }
    }

    /**
     * 안전한 suppressed 추가
     *
     * 자기 자신을 suppressed로 추가하려는 경우를 방어합니다.
     */
    private fun safeAddSuppressed(primary: Throwable, suppressed: Throwable?) {
        if (primary !== suppressed && suppressed != null) {
            try {
                primary.addSuppressed(suppressed)
            } catch (ignored: Throwable) {
                // addSuppressed 실패는 무시 (예: suppressed 비활성화된 예외)
            }
        }
    }
}
