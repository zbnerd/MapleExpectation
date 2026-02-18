package maple.expectation.infrastructure.executor.policy

/**
 * ExecutionPolicy 로그 태그 상수
 *
 * 정책들이 공통적으로 사용하는 로그 태그를 중앙에서 관리합니다.
 *
 * 같은 패키지 내에서만 사용되도록 internal 접근 제어자를 사용합니다.
 */
internal object TaskLogTags {
    /** 로그 태그: Task 시작 (BEFORE) */
    const val TAG_START = "[Task:START]"

    /** 로그 태그: Task 성공 (ON_SUCCESS, normal) */
    const val TAG_SUCCESS = "[Task:SUCCESS]"

    /** 로그 태그: Task 느린 성공 (ON_SUCCESS, slow) */
    const val TAG_SLOW = "[Task:SLOW]"

    /** 로그 태그: Task 실패 (ON_FAILURE) */
    const val TAG_FAILURE = "[Task:FAILURE]"

    /** 로그 태그: Task 종료 (AFTER) */
    const val TAG_AFTER = "[Task:AFTER]"

    /** 로그 태그: Task 정리 (FINALLY) */
    const val TAG_FINALLY = "[Task:FINALLY]"

    /** 로그 태그: 로깅 정책 (LOGGING) */
    const val TAG_LOGGING = "[Logging]"
}
