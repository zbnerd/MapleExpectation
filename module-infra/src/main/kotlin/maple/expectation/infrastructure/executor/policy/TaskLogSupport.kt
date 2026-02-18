package maple.expectation.infrastructure.executor.policy

import maple.expectation.infrastructure.executor.TaskContext

/**
 * ExecutionPolicy 로깅 유틸리티
 *
 * 정책들이 공통적으로 사용하는 로깅 관련 헬퍼 메서드를 제공합니다.
 */
internal object TaskLogSupport {
    private const val UNKNOWN = "unknown"
    private val WHITESPACE = Regex("\\s+")

    /**
     * TaskContext를 안전하게 문자열로 변환
     *
     * 로깅 실패가 본 실행 흐름에 영향을 주지 않도록 RuntimeException만 격리합니다.
     *
     * Error는 심각한 JVM 레벨 문제로 간주하여 전파합니다.
     *
     * @param context Task 실행 컨텍스트
     * @return Task 이름 (실패 시 "unknown" 또는 "unknown(ContextClassName)")
     */
    fun safeTaskName(context: TaskContext?): String {
        if (context == null) return UNKNOWN

        return try {
            val name = context.toTaskName()
            if (name == null) return UNKNOWN

            // 제어문자/공백 정규화 (로그 파서 안정성)
            val normalized = WHITESPACE.replace(name, " ").trim()
            if (normalized.isEmpty()) UNKNOWN else normalized
        } catch (e: RuntimeException) {
            val type = context::class.java
            val simple = type.simpleName
            val typeName = if (!simple.isNullOrBlank()) simple else "anonymous"
            "$UNKNOWN($typeName)"
        }
    }
}
