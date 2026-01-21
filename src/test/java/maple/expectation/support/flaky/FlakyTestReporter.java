package maple.expectation.support.flaky;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Flaky Test 이벤트 비동기 로거 (Issue #210)
 *
 * <p>Green Agent 최적화: SingleThreadExecutor로 파일 I/O 직렬화</p>
 * <p>Purple Agent 승인: 파이프 구분자 + 에러 메시지 이스케이프</p>
 *
 * <h3>로그 포맷:</h3>
 * <pre>
 * timestamp|gitCommit|className|methodName|errorMessage|retryCount
 * 2024-01-15T10:30:00.123Z|abc1234|FooTest|testMethod|NPE at line 42|1
 * </pre>
 *
 * <h3>P0 이슈 해결:</h3>
 * <ul>
 *   <li>파일 동시 쓰기: SingleThreadExecutor로 직렬화</li>
 *   <li>에러 메시지 파싱 오류: 파이프 문자 이스케이프</li>
 *   <li>테스트 성능 영향: 비동기 처리로 0ms 영향</li>
 * </ul>
 *
 * <p>CLAUDE.md Section 24 Observability 원칙 준수</p>
 */
@Slf4j
public class FlakyTestReporter {

    private static final String LOG_DIR = System.getProperty("flaky.log.dir", "build/flaky");
    private static final String EVENT_LOG_FILE = "flaky-events.log";

    /**
     * Green Agent 제안: Daemon 스레드로 JVM 종료 시 자동 종료
     * SingleThreadExecutor로 파일 I/O 직렬화 (동시 쓰기 방지)
     */
    private static final ExecutorService logWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "flaky-log-writer");
        t.setDaemon(true);  // JVM 종료 시 자동 종료
        return t;
    });

    /**
     * Flaky 이벤트를 비동기로 로그 파일에 기록
     *
     * @param event Flaky 이벤트 정보
     */
    public static void report(FlakyEvent event) {
        logWriter.submit(() -> writeToFile(event));
    }

    /**
     * 파일에 이벤트 기록 (동기 처리)
     */
    private static void writeToFile(FlakyEvent event) {
        try {
            Path logPath = Paths.get(LOG_DIR, EVENT_LOG_FILE);
            Files.createDirectories(logPath.getParent());

            // Purple Agent: 에러 메시지 이스케이프
            String escapedError = escapeErrorMessage(event.errorMessage());

            // 로그 라인 포맷: timestamp|commit|class|method|error|retryCount
            String line = String.format("%s|%s|%s|%s|%s|%d%n",
                    event.timestamp(),
                    event.gitCommit(),
                    event.className(),
                    event.methodName(),
                    escapedError,
                    event.retryCount()
            );

            Files.writeString(logPath, line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

            log.info("[FlakyTestReporter] Flaky test detected: {}.{} (commit: {})",
                    event.className(), event.methodName(), event.gitCommit());

        } catch (IOException e) {
            // P0: 로깅 실패가 테스트에 영향 없음
            log.warn("[FlakyTestReporter] Failed to write flaky log: {}", e.getMessage());
        }
    }

    /**
     * Purple Agent 제안: 파이프 문자와 개행 이스케이프
     */
    private static String escapeErrorMessage(String errorMessage) {
        if (errorMessage == null) {
            return "unknown";
        }
        return errorMessage
                .replace("|", "\\|")
                .replace("\n", " ")
                .replace("\r", " ");
    }
}
