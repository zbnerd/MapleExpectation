package maple.expectation.global.executor.policy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.TaskContext;
import org.springframework.core.annotation.Order;

import static maple.expectation.global.executor.policy.TaskLogTags.*;

/**
 * 자원 정리 작업을 after 훅에서 실행하는 정책 (Stateless)
 *
 * <p>성공/실패 여부와 관계없이 반드시 실행됩니다 (finally 블록).</p>
 *
 * <h3>사용 예시</h3>
 * <pre>{@code
 * ExecutionPipeline pipeline = new ExecutionPipeline(List.of(
 *     new LoggingPolicy(),
 *     new FinallyPolicy(lock::unlock)  // 락 해제
 * ));
 * }</pre>
 *
 * <h3>주요 용도</h3>
 * <ul>
 * <li>분산 락 해제</li>
 * <li>스트림/리소스 닫기</li>
 * <li>타이머/카운터 종료</li>
 * <li>임시 파일 삭제</li>
 * </ul>
 *
 * @since 2.4.0
 */
@Slf4j
@RequiredArgsConstructor
@Order(PolicyOrder.FINALLY)
public class FinallyPolicy implements ExecutionPolicy {

    private final Runnable cleanupTask;

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
