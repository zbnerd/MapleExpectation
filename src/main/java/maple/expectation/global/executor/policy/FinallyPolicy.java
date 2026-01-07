package maple.expectation.global.executor.policy;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.TaskContext;
import org.springframework.core.annotation.Order;

import java.util.Objects;

import static maple.expectation.global.executor.policy.TaskLogTags.*;

/**
 * 자원 정리 작업을 after 훅에서 실행하는 정책 (Stateless)
 *
 * <p>성공/실패 여부와 관계없이 반드시 실행됩니다 (finally 블록).</p>
 *
 * <h3>FinallyPolicy vs executeWithFinallyUnchecked (ADR)</h3>
 * <table border="1">
 *   <tr><th>항목</th><th>FinallyPolicy</th><th>executeWithFinallyUnchecked</th></tr>
 *   <tr><td>적용 범위</td><td>Pipeline 전체 (Bean 등록)</td><td>단일 호출 (메서드 인자)</td></tr>
 *   <tr><td>재사용성</td><td>높음 (여러 실행에 동일 정책)</td><td>1회성 (호출마다 지정)</td></tr>
 *   <tr><td>checked 예외</td><td>Runnable → unchecked만 허용</td><td>CheckedRunnable → checked 허용</td></tr>
 *   <tr><td>사용 시점</td><td>전역 정책 (로깅, 메트릭 등)</td><td>호출별 자원 해제 (락, 커넥션)</td></tr>
 * </table>
 *
 * <h3>⚠️ 중복 등록 금지</h3>
 * <p>동일한 finalizer를 FinallyPolicy와 executeWithFinallyUnchecked에 중복 등록하면
 * <b>2회 실행</b>되어 예기치 않은 동작(double-unlock 등)이 발생할 수 있습니다.</p>
 *
 * <h3>권장 패턴</h3>
 * <ul>
 *   <li><b>분산 락 해제</b>: {@code executeWithFinallyUnchecked} 사용 (호출별 1회 보장)</li>
 *   <li><b>전역 로깅/메트릭</b>: {@code FinallyPolicy} 사용 (Pipeline Bean 등록)</li>
 * </ul>
 *
 * <h3>사용 예시</h3>
 * <pre>{@code
 * // Pipeline 레벨 (전역)
 * ExecutionPipeline pipeline = new ExecutionPipeline(List.of(
 *     new LoggingPolicy(),
 *     new FinallyPolicy(this::recordMetrics)  // 전역 메트릭
 * ));
 *
 * // 호출 레벨 (락 해제) - 권장
 * return checkedExecutor.executeWithFinallyUnchecked(
 *     () -> doWorkUnderLock(),
 *     () -> lock.unlock(),
 *     context,
 *     e -> new LockException("Failed", e)
 * );
 * }</pre>
 *
 * @since 2.4.0
 * @see maple.expectation.global.executor.CheckedLogicExecutor#executeWithFinallyUnchecked
 */
@Slf4j
@Order(PolicyOrder.FINALLY)
public class FinallyPolicy implements ExecutionPolicy {

    private final Runnable cleanupTask;
    private final FailureMode failureMode;

    /**
     * PROPAGATE 모드로 FinallyPolicy 생성 (기본값, 권장)
     *
     * <p>cleanup 실패가 외부로 전파되어 관측 가능합니다 (try/finally 의미론).</p>
     * <ul>
     *   <li>task 성공 + cleanup 실패 → cleanup 예외가 외부로 전파됨</li>
     *   <li>task 실패 + cleanup 실패 → cleanup 예외는 suppressed로 보존</li>
     * </ul>
     *
     * @param cleanupTask 반드시 실행할 정리 작업
     */
    public FinallyPolicy(Runnable cleanupTask) {
        this(cleanupTask, FailureMode.PROPAGATE);
    }

    /**
     * 지정된 FailureMode로 FinallyPolicy 생성
     *
     * <h4>FailureMode 선택 가이드 (금융급)</h4>
     * <ul>
     *   <li><b>PROPAGATE (기본, 권장)</b>: cleanup 실패가 외부로 전파됨
     *     <ul>
     *       <li>용도: 락 해제, 커넥션 반납 등 "성공을 가장하면 안 되는" 정리 작업</li>
     *       <li>효과: try/finally와 동일한 의미론 보장</li>
     *     </ul>
     *   </li>
     *   <li><b>SWALLOW</b>: cleanup 실패가 흡수되어 본 흐름 영향 없음
     *     <ul>
     *       <li>용도: 전역 메트릭/로깅 등 부가적 after hook</li>
     *       <li>⚠️ 주의: cleanup 실패가 "성공을 가장"할 수 있으므로, 반드시 WARN 로그 등으로 가시화 필요</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param cleanupTask 반드시 실행할 정리 작업
     * @param failureMode 실패 처리 모드
     */
    public FinallyPolicy(Runnable cleanupTask, FailureMode failureMode) {
        this.cleanupTask = Objects.requireNonNull(cleanupTask, "cleanupTask must not be null");
        this.failureMode = Objects.requireNonNull(failureMode, "failureMode must not be null");
    }

    @Override
    public FailureMode failureMode() {
        return failureMode;
    }

    @Override
    public void after(ExecutionOutcome outcome, long elapsedNanos, TaskContext context) {
        if (log.isDebugEnabled()) {
            String taskName = TaskLogSupport.safeTaskName(context);
            log.debug("{} Cleaning up for {} (outcome={})", TAG_FINALLY, taskName, outcome);
        }
        cleanupTask.run();
    }

    /**
     * 테스트에서 policy가 보유한 action을 확인/호출하기 위한 접근자.
     * - 외부 API로 노출되어도 의미적으로 무해한 값이며, 디버깅에도 유용하다.
     */
    public Runnable action() {
        return cleanupTask;
    }
}
