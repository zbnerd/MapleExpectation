package maple.expectation.global.executor.policy;

/**
 * ExecutionPolicy 로그 태그 상수
 *
 * <p>정책들이 공통적으로 사용하는 로그 태그를 중앙에서 관리합니다.</p>
 * <p>같은 패키지 내에서만 사용되도록 package-private 접근 제어자를 사용합니다.</p>
 *
 * @since 2.4.0
 */
final class TaskLogTags {

    /** 로그 태그: Task 시작 (BEFORE) */
    static final String TAG_START = "[Task:START]";

    /** 로그 태그: Task 성공 (ON_SUCCESS, normal) */
    static final String TAG_SUCCESS = "[Task:SUCCESS]";

    /** 로그 태그: Task 느린 성공 (ON_SUCCESS, slow) */
    static final String TAG_SLOW = "[Task:SLOW]";

    /** 로그 태그: Task 실패 (ON_FAILURE) */
    static final String TAG_FAILURE = "[Task:FAILURE]";

    /** 로그 태그: Task 종료 (AFTER) */
    static final String TAG_AFTER = "[Task:AFTER]";

    /** 로그 태그: Task 정리 (FINALLY) */
    static final String TAG_FINALLY = "[Task:FINALLY]";

    private TaskLogTags() {
    }
}
