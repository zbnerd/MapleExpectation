package maple.expectation.infrastructure.executor

/**
 * 메트릭 카디널리티 통제를 위한 작업 컨텍스트
 *
 * TaskName을 구조화하여 동적 값과 고정 Taxonomy를 분리합니다.
 *
 * ## 형식
 * ```
 * "component:operation:dynamicValue"
 *
 * 예시:
 * - TaskContext.of("Observability", "track", "GameCharacterService.createNewCharacter")
 *   → "Observability:track:GameCharacterService.createNewCharacter"
 * - TaskContext.of("Trace", "execute")
 *   → "Trace:execute"
 * ```
 *
 * ## P1 정책: 메트릭 카디널리티 통제
 * - component, operation: 메트릭 태그로 사용 (고정 값)
 * - dynamicValue: 로그에만 기록 (메트릭에서 제외)
 *
 * @property component 컴포넌트 이름 (예: "Observability", "Trace", "Lock")
 * @property operation 작업 유형 (예: "track", "execute", "acquire")
 * @property dynamicValue 동적 값 (예: 메서드 시그니처, 파라미터)
 */
data class TaskContext(
    val component: String,
    val operation: String,
    val dynamicValue: String = ""
) {
    init {
        require(component.isNotBlank()) { "component must not be blank" }
        require(operation.isNotBlank()) { "operation must not be blank" }
    }

    /**
     * TaskName 문자열로 변환
     *
     * DefaultLogicExecutor의 parseTaskName()과 호환되는 형식으로 변환합니다.
     *
     * @return "component:operation:dynamicValue" 형식의 문자열
     */
    fun toTaskName(): String = if (dynamicValue.isEmpty()) {
        "$component:$operation"
    } else {
        "$component:$operation:$dynamicValue"
    }

    companion object {
        /**
         * TaskContext 생성 (동적 값 없음)
         */
        @JvmStatic
        fun of(component: String, operation: String): TaskContext =
            TaskContext(component, operation, "")

        /**
         * TaskContext 생성 (동적 값 포함)
         */
        @JvmStatic
        fun of(component: String, operation: String, dynamicValue: String): TaskContext =
            TaskContext(component, operation, dynamicValue)
    }
}
