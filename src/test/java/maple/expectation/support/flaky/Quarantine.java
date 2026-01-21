package maple.expectation.support.flaky;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Flaky Test 격리 마커 어노테이션 (Issue #210)
 *
 * <p>7일 내 3회 이상 Flaky 발생 시 부착하여 PR Gate에서 제외합니다.</p>
 *
 * <h3>Quarantine 정책 (Purple Agent 승인):</h3>
 * <ul>
 *   <li>진입 조건: 7일 내 3회 이상 Flaky 발생</li>
 *   <li>해제 조건: 10회 연속 Pass</li>
 *   <li>PR Gate: 제외 (excludeTags 'quarantine')</li>
 *   <li>Nightly: 포함 (testWithQuarantine task)</li>
 * </ul>
 *
 * <h3>사용 예시:</h3>
 * <pre>{@code
 * @Quarantine(
 *     reason = "Redis Sentinel failover timing issue",
 *     since = "2024-01-15",
 *     owner = "geek",
 *     issue = 210
 * )
 * @Test
 * void flakyTest() {
 *     // 간헐적으로 실패하는 테스트
 * }
 * }</pre>
 *
 * <p>CLAUDE.md Section 24 준수: Isolation 원칙</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("quarantine")
public @interface Quarantine {

    /**
     * Flaky 발생 사유 (필수)
     *
     * <p>예: "Redis Sentinel failover timing issue"</p>
     */
    String reason();

    /**
     * 격리 시작일 (필수)
     *
     * <p>형식: YYYY-MM-DD (예: "2024-01-15")</p>
     */
    String since();

    /**
     * 담당자 GitHub ID (선택)
     *
     * <p>Quarantine 해제 담당자</p>
     */
    String owner() default "";

    /**
     * 관련 GitHub Issue 번호 (선택)
     *
     * <p>Flaky 수정을 위한 Issue 번호</p>
     */
    int issue() default 0;
}
