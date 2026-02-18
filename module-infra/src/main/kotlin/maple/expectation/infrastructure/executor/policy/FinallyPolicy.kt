package maple.expectation.infrastructure.executor.policy

import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order

/**
 * 자원 정리 작업을 after 훅에서 실행하는 정책 (Stateless)
 *
 * 성공/실패 여부와 관계없이 반드시 실행됩니다 (finally 블록).
 *
 * ## FinallyPolicy vs executeWithFinallyUnchecked (ADR)
 *
 * | 항목 | FinallyPolicy | executeWithFinallyUnchecked |
 * |------|---------------|------------------------------|
 * | 적용 범위 | Pipeline 전체 (Bean 등록) | 단일 호출 (메서드 인자) |
 * | 재사용성 | 높음 (여러 실행에 동일 정책) | 1회성 (호출마다 지정) |
 * | checked 예외 | Runnable → unchecked만 허용 | CheckedRunnable → checked 허용 |
 * | 사용 시점 | 전역 정책 (로깅, 메트릭 등) | 호출별 자원 해제 (락, 커넥션) |
 *
 * ## ⚠️ 중복 등록 금지
 *
 * 동일한 finalizer를 FinallyPolicy와 executeWithFinallyUnchecked에 중복 등록하면 **2회 실행**되어
 * 예기치 않은 동작(double-unlock 등)이 발생할 수 있습니다.
 *
 * ## 권장 패턴
 * - **분산 락 해제**: {@code executeWithFinallyUnchecked} 사용 (호출별 1회 보장)
 * - **전역 로깅/메트릭**: {@code FinallyPolicy} 사용 (Pipeline Bean 등록)
 *
 * ## 사용 예시
 * ```kotlin
 * // Pipeline 레벨 (전역)
 * val pipeline = ExecutionPipeline(listOf(
 *     LoggingPolicy(),
 *     FinallyPolicy { recordMetrics() }  // 전역 메트릭
 * ))
 *
 * // 호출 레벨 (락 해제) - 권장
 * return checkedExecutor.executeWithFinallyUnchecked(
 *     task = { doWorkUnderLock() },
 *     finalizer = { lock.unlock() },
 *     context = context,
 *     mapper = { e -> LockException("Failed", e) }
 * )
 * ```
 */
@Order(PolicyOrder.FINALLY)
class FinallyPolicy(
    private val cleanupTask: Runnable,
    private val failureMode: FailureMode
) : ExecutionPolicy {

    private val log = LoggerFactory.getLogger(FinallyPolicy::class.java)

    /**
     * PROPAGATE 모드로 FinallyPolicy 생성 (기본값, 권장)
     *
     * cleanup 실패가 외부로 전파되어 관측 가능합니다 (try/finally 의미론).
     * - task 성공 + cleanup 실패 → cleanup 예외가 외부로 전파됨
     * - task 실패 + cleanup 실패 → cleanup 예외는 suppressed로 보존
     */
    constructor(cleanupTask: Runnable) : this(cleanupTask, FailureMode.PROPAGATE)

    init {
        require(cleanupTask != null) { "cleanupTask must not be null" }
        require(failureMode != null) { "failureMode must not be null" }
    }

    override fun failureMode(): FailureMode = failureMode

    override fun after(outcome: ExecutionOutcome, elapsedNanos: Long, context: TaskContext) {
        if (log.isDebugEnabled) {
            val taskName = TaskLogSupport.safeTaskName(context)
            log.debug("{} Cleaning up for {} (outcome={})", TaskLogTags.TAG_FINALLY, taskName, outcome)
        }
        cleanupTask.run()
    }

    /**
     * 테스트에서 policy가 보유한 action을 확인/호출하기 위한 접근자.
     *
     * 외부 API로 노출되어도 의미적으로 무해한 값이며, 디버깅에도 유용하다.
     */
    fun action(): Runnable = cleanupTask
}
