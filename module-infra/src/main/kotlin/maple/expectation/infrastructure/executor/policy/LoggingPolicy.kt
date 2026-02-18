package maple.expectation.infrastructure.executor.policy

import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order

/**
 * 실행 시간 및 성공/실패를 로깅하는 정책 (Stateless)
 *
 * ## 기능
 * - **Slow Task 탐지**: 실행 시간이 설정값(slowMs)을 초과하면 WARN 레벨로 로깅
 * - **성공/실패 로깅**: DEBUG 레벨로 task 실행 결과 로깅
 * - **예외 로깅**: ERROR 레벨로 예외 스택 트레이스 로깅
 *
 * ## 설정
 * {@code maple.executor.logging.slow-ms} 속성으로 slow task 기준 시간을 설정할 수 있습니다.
 * 기본값: 3000ms (3초)
 *
 * @param slowMs Slow task로 간주할 최소 실행 시간 (밀리초)
 */
@Order(PolicyOrder.LOGGING)
class LoggingPolicy(private val slowMs: Long = 3000L) : ExecutionPolicy {

    private val log = LoggerFactory.getLogger(LoggingPolicy::class.java)

    init {
        require(slowMs > 0) { "slowMs must be positive: $slowMs" }
    }

    override fun before(context: TaskContext) {
        if (log.isDebugEnabled) {
            val taskName = TaskLogSupport.safeTaskName(context)
            log.debug("{} Starting {}", TaskLogTags.TAG_LOGGING, taskName)
        }
    }

    override fun <T> onSuccess(result: T, elapsedNanos: Long, context: TaskContext) {
        val taskName = TaskLogSupport.safeTaskName(context)
        val elapsedMs = elapsedNanos / 1_000_000

        if (elapsedMs >= slowMs) {
            log.warn(
                "{} Slow task detected: {} ({}ms)",
                TaskLogTags.TAG_LOGGING,
                taskName,
                elapsedMs
            )
        } else if (log.isDebugEnabled) {
            log.debug(
                "{} Task succeeded: {} ({}ms)",
                TaskLogTags.TAG_LOGGING,
                taskName,
                elapsedMs
            )
        }
    }

    override fun onFailure(error: Throwable, elapsedNanos: Long, context: TaskContext) {
        val taskName = TaskLogSupport.safeTaskName(context)
        val elapsedMs = elapsedNanos / 1_000_000

        log.error(
            "{} Task failed: {} ({}ms)",
            TaskLogTags.TAG_LOGGING,
            taskName,
            elapsedMs,
            error
        )
    }

    /**
     * Slow task 기준 시간을 반환합니다.
     *
     * 테스트에서 policy 설정을 확인/검증하기 위한 접근자입니다.
     */
    fun slowThresholdMillis(): Long = slowMs
}
