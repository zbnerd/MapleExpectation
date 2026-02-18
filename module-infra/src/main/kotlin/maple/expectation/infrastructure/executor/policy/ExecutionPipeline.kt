package maple.expectation.infrastructure.executor.policy

import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.util.InterruptUtils
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ExecutionPolicy를 순차 실행하는 파이프라인.
 *
 * ## 핵심 보장 (PRD v4 준수)
 * - **elapsedNanos는 task.get() 구간만 측정** (정책 시간 제외)
 * - **BEFORE는 등록 순서, AFTER는 역순(LIFO)**
 * - **before() 성공한 정책만 after() 호출** (entered pairing)
 * - **BEFORE(PROPAGATE) 실패 시 onFailure 미호출**: task가 시작되지 않았으므로
 *   onFailure를 호출하지 않음 (entered 정책만 after로 정리)
 * - **onSuccess/onFailure는 관측 훅**: non-Error 예외는 결과(성공/실패/전파 타입)를 변경하지 않음.
 *   단, onFailure 훅 자체 실패(**non-Error**)는 원인(cause)에 suppressed로 보존함
 * - **task Error에도 onFailure best-effort 호출**: task가 Error로 실패해도 onFailure는 best-effort로 실행함.
 *   단, onFailure 내부에서 Error 발생 시 즉시 중단함
 * - **Error는 최우선 전파 대상**: 훅 단계에서 Error가 발생하면 우선 전파 대상으로 승격하며,
 *   AFTER unwind를 끝까지 시도한 뒤 최종 throw함
 * - **실패 경로에서 후속 예외는 suppressed로 보존**: primary가 존재하면 이후 예외는 suppressed로만 누적함
 * - **성공 경로에서 AFTER 실패는 primary가 될 수 있음**: primary가 없으면 최초 AFTER 예외를
 *   최종 throw 대상으로 설정함
 * - **outcome은 정책 훅 실패와 무관하게 task 실행 결과만을 의미함**: after(outcome, ...)에
 *   전달되는 outcome은 훅/정책 실패를 반영하지 않고, 오직 task.get()의 성공/실패만을 나타냄
 * - **ThreadLocal depth로 재진입 폭주 fail-fast** (MAX_NESTING_DEPTH)
 */
class ExecutionPipeline(policies: List<ExecutionPolicy>) {
    private val log = LoggerFactory.getLogger(ExecutionPipeline::class.java)

    private val slots: List<Slot>

    init {
        requireNotNull(policies) { "policies must not be null" }

        val temp = mutableListOf<Slot>()
        for ((index, p) in policies.withIndex()) {
            requireNotNull(p) { "policies[$index] is null" }
            val mode = requireNotNull(p.failureMode()) { "policies[$index].failureMode() is null" }
            temp.add(Slot(p, mode))
        }
        slots = temp.toList()
    }

    private data class Slot(val policy: ExecutionPolicy, val mode: FailureMode)

    /**
     * Supplier 기반 실행 (원본 Throwable 그대로 전파)
     */
    fun <T> executeRaw(task: ThrowingSupplier<T>, context: TaskContext): T {
        requireNotNull(task) { "task must not be null" }
        requireNotNull(context) { "context must not be null" }

        // 0) reentrancy guard
        val depth = nestingDepth.get() + 1
        nestingDepth.set(depth)

        if (depth > MAX_NESTING_DEPTH) {
            // 누수 방지 복구
            val prev = depth - 1
            if (prev <= 0) nestingDepth.remove()
            else nestingDepth.set(prev)

            log.error(
                "[Pipeline:REENTRANCY] nesting depth exceeded. depth={}, limit={}, contextType={}, contextHash={}",
                depth,
                MAX_NESTING_DEPTH,
                context::class.simpleName,
                System.identityHashCode(context)
            )

            throw IllegalStateException(
                "ExecutionPipeline nesting depth exceeded ($MAX_NESTING_DEPTH). " +
                    "Possible recursion loop."
            )
        }

        val taskName = safeToTaskName(context)

        try {
            // ========== 변수 선언 ==========
            val entered = mutableListOf<Slot>()
            var taskOutcome = ExecutionOutcome.FAILURE

            var taskStarted = false
            var taskStartNanos = 0L
            var elapsedNanos: Long? = null // null = 미확정

            var primary: Throwable? = null // 최종 throw 후보
            var result: T? = null

            // ========== PHASE 1: BEFORE (lifecycle 훅) ==========
            try {
                for (slot in slots) {
                    if (invokeBefore(slot, context, taskName)) {
                        entered.add(slot) // before 성공한 policy만 entered
                    }
                }
            } catch (t: Throwable) {
                InterruptUtils.restoreInterruptIfNeeded(t)
                primary = t // BEFORE PROPAGATE 실패 시 task 미실행
                // onFailure는 BEFORE PROPAGATE 실패 시 호출하지 않음 (PRD 표 8.1)
            }

            // ========== PHASE 2: TASK + ON_FAILURE ==========
            if (primary == null) {
                try {
                    taskStarted = true
                    taskStartNanos = System.nanoTime()
                    result = task.get()
                    elapsedNanos = System.nanoTime() - taskStartNanos

                    // task 성공 직후 outcome 확정 (ON_SUCCESS 전)
                    taskOutcome = ExecutionOutcome.SUCCESS
                } catch (t: Throwable) {
                    InterruptUtils.restoreInterruptIfNeeded(t)

                    // elapsed 계산 (task 실패/Error 포함)
                    if (taskStarted && elapsedNanos == null) {
                        elapsedNanos = System.nanoTime() - taskStartNanos
                    }
                    val elapsed = elapsedNanos ?: 0L

                    primary = t // task 예외를 primary로 설정

                    // ON_FAILURE: task Error여도 best-effort로 실행 (PRD 4.5-5 선택 A)
                    for (slot in entered) {
                        try {
                            invokeOnFailure(slot, primary, elapsed, context, taskName)
                        } catch (err: Error) {
                            log.error(
                                "[Pipeline:CRITICAL] Error in onFailure hook. policy={}, taskName={}",
                                policyName(slot.policy),
                                taskName,
                                err
                            )
                            primary = promoteError(primary, err)
                            break // onFailure Error 시 즉시 중단 (PRD 4.5 확장)
                        }
                    }
                }
            }

            // ========== PHASE 3: ON_SUCCESS (task 성공 시에만) ==========
            if (primary == null && taskOutcome == ExecutionOutcome.SUCCESS) {
                val elapsed = elapsedNanos ?: 0L // 방어 패턴
                for (slot in entered) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        invokeOnSuccess(slot, result as T, elapsed, context, taskName)
                    } catch (err: Error) {
                        log.error(
                            "[Pipeline:CRITICAL] Error in onSuccess hook. policy={}, taskName={}",
                            policyName(slot.policy),
                            taskName,
                            err
                        )
                        primary = promoteError(primary, err)
                        break // Error 발생 시 onFailure 스킵, 즉시 after로 (PRD 4.5)
                    }
                }
            }

            // ========== PHASE 4: AFTER LIFO (무조건 끝까지 unwind) ==========
            // elapsed 최종 확정 (SG2: 동일 값 전달)
            val elapsed = when {
                elapsedNanos != null -> elapsedNanos
                taskStarted -> System.nanoTime() - taskStartNanos
                else -> 0L
            }

            // AFTER: N -> 0 (LIFO, loop는 break하지 않음)
            for (i in entered.size - 1 downTo 0) {
                val slot = entered[i]
                try {
                    invokeAfter(slot, taskOutcome, elapsed, context, taskName)
                } catch (err: Error) {
                    log.error(
                        "[Pipeline:CRITICAL] Error in after hook. policy={}, taskName={}",
                        policyName(slot.policy),
                        taskName,
                        err
                    )
                    primary = promoteError(primary, err)
                    // Error여도 after unwind 계속 수행 (PRD 4.3)
                } catch (afterEx: Throwable) {
                    InterruptUtils.restoreInterruptIfNeeded(afterEx)

                    if (primary != null) {
                        // 실패 경로: after 실패는 suppressed로만 보존
                        addSuppressedSafely(primary, afterEx)
                    } else {
                        // 성공 경로: after 실패가 새로운 Primary
                        primary = afterEx
                    }
                }
            }

            // ========== 단일 throw 지점 (메서드 말미, 예외 마스킹 없음) ==========
            if (primary != null) {
                throw primary
            }
            @Suppress("UNCHECKED_CAST")
            return result as T
        } finally {
            // depth 복구 (반드시 실행)
            val cur = nestingDepth.get() - 1
            if (cur <= 0) nestingDepth.remove()
            else nestingDepth.set(cur)
        }
    }

    /**
     * Runnable 편의 메서드
     */
    fun executeRaw(task: ThrowingRunnable, context: TaskContext) {
        requireNotNull(task) { "task must not be null" }
        executeRaw(
            ThrowingSupplier<Nothing> {
                task.run()
                null
            },
            context
        )
    }

    /**
     * 기존 정책에 추가 정책을 병합한 새로운 Pipeline 생성
     *
     * executeWithFinally() 등에서 동적으로 정책을 추가할 때 사용합니다.
     *
     * 기존 Pipeline의 정책 + 추가 정책 순서로 병합됩니다 (BEFORE는 순서대로, AFTER는 역순).
     */
    fun withAdditionalPolicies(additionalPolicies: List<ExecutionPolicy>): ExecutionPipeline {
        requireNotNull(additionalPolicies) { "additionalPolicies must not be null" }

        val merged = mutableListOf<ExecutionPolicy>()
        for (slot in slots) {
            merged.add(slot.policy)
        }
        merged.addAll(additionalPolicies)

        return ExecutionPipeline(merged)
    }

    // ========================================
    // Private Helpers
    // ========================================

    private fun invokeBefore(slot: Slot, context: TaskContext, taskName: String): Boolean {
        return try {
            slot.policy.before(context)
            true
        } catch (e: Error) {
            throw e
        } catch (t: Throwable) {
            InterruptUtils.restoreInterruptIfNeeded(t)
            log.warn(
                "[Policy:BEFORE] failed. policy={}, taskName={}",
                policyName(slot.policy),
                taskName,
                t
            )

            if (slot.mode == FailureMode.PROPAGATE) {
                throw t
            }
            false
        }
    }

    private fun <T> invokeOnSuccess(
        slot: Slot,
        result: T,
        elapsedNanos: Long,
        context: TaskContext,
        taskName: String
    ) {
        try {
            slot.policy.onSuccess(result, elapsedNanos, context)
        } catch (err: Error) {
            throw err
        } catch (t: Throwable) {
            InterruptUtils.restoreInterruptIfNeeded(t)
            log.warn(
                "[Policy:ON_SUCCESS] failed. policy={}, taskName={}",
                policyName(slot.policy),
                taskName,
                t
            )
            // non-Error는 always swallow
        }
    }

    private fun invokeOnFailure(
        slot: Slot,
        cause: Throwable?,
        elapsedNanos: Long,
        context: TaskContext,
        taskName: String
    ) {
        if (cause == null) return

        try {
            slot.policy.onFailure(cause, elapsedNanos, context)
        } catch (err: Error) {
            throw err
        } catch (t: Throwable) {
            InterruptUtils.restoreInterruptIfNeeded(t)
            log.warn(
                "[Policy:ON_FAILURE] failed. policy={}, taskName={}",
                policyName(slot.policy),
                taskName,
                t
            )
            // 관측 훅 실패는 원인 추적 위해 cause에 suppressed로 보존
            addSuppressedSafely(cause, t)
        }
    }

    private fun invokeAfter(
        slot: Slot,
        outcome: ExecutionOutcome,
        elapsedNanos: Long,
        context: TaskContext,
        taskName: String
    ) {
        try {
            slot.policy.after(outcome, elapsedNanos, context)
        } catch (e: Error) {
            throw e
        } catch (t: Throwable) {
            InterruptUtils.restoreInterruptIfNeeded(t)
            log.warn(
                "[Policy:AFTER] failed. policy={}, taskName={}",
                policyName(slot.policy),
                taskName,
                t
            )

            if (slot.mode == FailureMode.PROPAGATE) {
                throw t
            }
            // SWALLOW
        }
    }

    private fun promoteError(currentPrimary: Throwable?, newError: Error): Throwable {
        return if (currentPrimary == null) {
            newError
        } else {
            if (currentPrimary is Error) {
                addSuppressedSafely(currentPrimary, newError)
                currentPrimary // 최초 Error 유지
            } else {
                addSuppressedSafely(newError, currentPrimary)
                newError
            }
        }
    }

    private fun addSuppressedSafely(primary: Throwable?, suppressed: Throwable?) {
        if (primary == null || suppressed == null) return
        if (primary === suppressed) return

        try {
            primary.addSuppressed(suppressed)
        } catch (e: Exception) {
            log.debug(
                "addSuppressed failed. primary={}, suppressed={}",
                primary.javaClass.name,
                suppressed.javaClass.name,
                e
            )
        }
    }

    private fun policyName(policy: ExecutionPolicy?): String {
        return policy?.javaClass?.simpleName ?: "null"
    }

    private fun safeToTaskName(context: TaskContext?): String {
        if (context == null) return "unknown"
        return try {
            context.toTaskName().toString()
        } catch (t: Throwable) {
            "unknown"
        }
    }

    companion object {
        private const val MAX_NESTING_DEPTH = 32

        /**
         * V5 Stateless Architecture 검증 완료 (#271):
         *
         * - 용도: Reentrancy 폭주 방지 (fail-fast)
         * - 범위: 요청 내 일시적 상태, cross-request 상태 아님
         * - 정리: finally 블록에서 depth==0이면 remove() 호출
         * - MDC 전환 불필요: 고빈도 작업, 내부 구현 상세
         */
        private val nestingDepth = ThreadLocal.withInitial { 0 }
    }
}
