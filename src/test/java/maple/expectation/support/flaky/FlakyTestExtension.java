package maple.expectation.support.flaky;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

import java.time.Instant;

/**
 * Flaky Test 감지 Extension (Issue #210)
 *
 * <p>FAIL → PASS 패턴을 감지하여 Flaky 이벤트를 기록합니다.</p>
 *
 * <h3>5-Agent Council 합의:</h3>
 * <ul>
 *   <li>Blue: ThreadLocal 대신 ExtensionContext.Store 사용 → 메모리 누수 방지</li>
 *   <li>Red: 모든 예외 격리 → 테스트 실행에 영향 없음</li>
 *   <li>Yellow: test-retry 플러그인과 연동하여 재시도 감지</li>
 *   <li>Green: 비동기 로깅으로 테스트 성능 영향 최소화</li>
 *   <li>Purple: 로그 포맷 표준화 및 에러 메시지 이스케이프</li>
 * </ul>
 *
 * <h3>동작 원리:</h3>
 * <ol>
 *   <li>테스트 실행 시작 (beforeTestExecution)</li>
 *   <li>예외 발생 시 Store에 실패 정보 저장 (handleTestExecutionException)</li>
 *   <li>test-retry 플러그인이 재실행</li>
 *   <li>재실행 성공 시 Store에서 이전 실패 정보 확인 → Flaky 감지 (afterTestExecution)</li>
 * </ol>
 *
 * <p>CLAUDE.md Section 24 준수: Determinism, Isolation, Independence, Observability</p>
 */
@Slf4j
public class FlakyTestExtension implements
        BeforeTestExecutionCallback,
        AfterTestExecutionCallback,
        TestExecutionExceptionHandler {

    /**
     * Blue Agent 제안: ExtensionContext.Store용 네임스페이스
     * 테스트 간 상태 격리 보장
     */
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(FlakyTestExtension.class);

    private static final String FAILURE_KEY = "testFailure";

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        // 테스트 시작 시 이전 실패 정보 정리 (재시도 시 누적 방지)
        // Note: Store는 테스트 인스턴스별로 격리되므로 명시적 정리 불필요
    }

    /**
     * 테스트 예외 발생 시 실패 정보 저장
     *
     * <p>P0: Red Agent 제안 - 예외 격리로 테스트 실행에 영향 없음</p>
     */
    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable)
            throws Throwable {
        try {
            // Blue Agent: ExtensionContext.Store에 실패 정보 저장
            context.getStore(NAMESPACE).put(FAILURE_KEY, new TestFailureInfo(
                    context.getRequiredTestClass().getName(),
                    context.getRequiredTestMethod().getName(),
                    throwable.getMessage(),
                    Instant.now()
            ));

            log.debug("[FlakyTestExtension] Test failed: {}.{} - {}",
                    context.getRequiredTestClass().getSimpleName(),
                    context.getRequiredTestMethod().getName(),
                    throwable.getClass().getSimpleName());

        } catch (Exception e) {
            // P0: Red Agent 제안 - 예외 격리 (테스트 실행에 영향 없음)
            log.warn("[FlakyTestExtension] handleTestExecutionException failed: {}", e.getMessage());
        }

        // 원래 예외 재전파 (test-retry가 처리)
        throw throwable;
    }

    /**
     * 테스트 완료 후 Flaky 감지
     *
     * <p>이전에 실패했으나 현재 성공 = Flaky 테스트</p>
     */
    @Override
    public void afterTestExecution(ExtensionContext context) {
        try {
            // Store에서 이전 실패 정보 조회 및 제거
            TestFailureInfo previousFailure = context.getStore(NAMESPACE)
                    .remove(FAILURE_KEY, TestFailureInfo.class);

            // 이전 실패 O + 현재 성공 = Flaky 감지!
            if (previousFailure != null && context.getExecutionException().isEmpty()) {
                FlakyEvent event = FlakyEvent.of(
                        previousFailure.className(),
                        previousFailure.methodName(),
                        previousFailure.errorMessage(),
                        getGitCommit()
                );

                // Green Agent: 비동기 로깅 (테스트 성능 영향 없음)
                FlakyTestReporter.report(event);

                log.warn("[FlakyTestExtension] FLAKY TEST DETECTED: {}.{} - passed on retry",
                        previousFailure.className(),
                        previousFailure.methodName());
            }

        } catch (Exception e) {
            // P0: 예외 격리 (테스트 실행에 영향 없음)
            log.warn("[FlakyTestExtension] afterTestExecution failed: {}", e.getMessage());
        }
    }

    /**
     * Git 커밋 해시 획득
     *
     * <p>Purple Agent 제안: CI 환경변수 → 로컬 환경 → "unknown" 순서</p>
     *
     * @return 7자리 커밋 해시 또는 "unknown"
     */
    private String getGitCommit() {
        // GitHub Actions: GITHUB_SHA 환경변수
        String sha = System.getenv("GITHUB_SHA");
        if (sha != null && sha.length() >= 7) {
            return sha.substring(0, 7);
        }

        // GitLab CI: CI_COMMIT_SHA 환경변수
        sha = System.getenv("CI_COMMIT_SHA");
        if (sha != null && sha.length() >= 7) {
            return sha.substring(0, 7);
        }

        // 로컬 환경: unknown 반환 (git 명령 실행은 오버헤드)
        return "unknown";
    }
}
