package maple.expectation.global.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.function.CheckedRunnable;
import maple.expectation.global.executor.function.CheckedSupplier;
import maple.expectation.global.executor.policy.ExecutionPipeline;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Checked 예외를 그대로 전파하는 Executor 구현체
 *
 * <p>ExecutionPipeline 기반으로 checked 예외를 계약 타입 그대로 전파하며,
 * 계약 위반 시 IllegalStateException을 던져 fail-fast합니다.</p>
 *
 * <h3>금융급 보장사항</h3>
 * <ul>
 *   <li><b>계약 타입 보존</b>: expectedExceptionType만 전파, 그 외는 계약 위반으로 간주</li>
 *   <li><b>RuntimeException 금지</b>: checked 예외만 계약 타입으로 허용</li>
 *   <li><b>Error 즉시 전파</b>: VirtualMachineError 등은 번역 없이 rethrow</li>
 *   <li><b>인터럽트 플래그 복원</b>: InterruptedException 발생 시 Thread.currentThread().interrupt()</li>
 *   <li><b>null 방어</b>: 모든 파라미터 null 체크</li>
 * </ul>
 *
 * @since 2.4.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultCheckedLogicExecutor implements CheckedLogicExecutor {

    private final ExecutionPipeline pipeline;

    @Override
    public <T, E extends Exception> T execute(
            CheckedSupplier<T, E> task,
            Class<E> expectedExceptionType,
            TaskContext context
    ) throws E {
        // 1. null 방어
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(expectedExceptionType, "expectedExceptionType must not be null");
        Objects.requireNonNull(context, "context must not be null");

        // 2. RuntimeException 금지 (unchecked이므로 계약 타입 부적합)
        if (RuntimeException.class.isAssignableFrom(expectedExceptionType)) {
            throw new IllegalArgumentException(
                    "RuntimeException cannot be used as contract type (use LogicExecutor for unchecked exceptions). " +
                    "Got: " + expectedExceptionType.getName()
            );
        }

        try {
            // 3. ExecutionPipeline으로 실행 (executeRaw는 Throwable 그대로 전파)
            return pipeline.executeRaw(task::get, context);

        } catch (Error e) {
            // 4. Error는 즉시 전파 (번역 금지)
            throw e;

        } catch (RuntimeException e) {
            // 5. RuntimeException은 계약 위반 아님 (unchecked이므로 그대로 전파)
            throw e;

        } catch (Exception e) {
            // 6. 인터럽트 플래그 복원
            restoreInterruptIfNeeded(e);

            // 7. 계약 타입 확인
            if (expectedExceptionType.isInstance(e)) {
                // 계약 타입: 그대로 캐스팅하여 전파
                @SuppressWarnings("unchecked")
                E contractException = (E) e;
                throw contractException;
            } else {
                // 계약 위반: 다른 checked 예외 발생
                throw new IllegalStateException(
                        "Contract violation: expected " + expectedExceptionType.getSimpleName() +
                        ", but got " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        e
                );
            }
        } catch (Throwable t) {
            // 8. Throwable은 예기치 못한 상황 (ExecutionPipeline이 정상이라면 발생 안 함)
            throw new IllegalStateException(
                    "Unexpected Throwable (not Error/Exception): " + t.getClass().getName() +
                    ": " + t.getMessage(),
                    t
            );
        }
    }

    @Override
    public <E extends Exception> void executeVoid(
            CheckedRunnable<E> task,
            Class<E> expectedExceptionType,
            TaskContext context
    ) throws E {
        execute(() -> {
            task.run();
            return null;
        }, expectedExceptionType, context);
    }

    /**
     * InterruptedException 발생 시 인터럽트 플래그 복원
     *
     * <p>cause chain을 순회하여 InterruptedException이 있으면 Thread.currentThread().interrupt() 호출</p>
     */
    private void restoreInterruptIfNeeded(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        final int MAX_DEPTH = 32; // 무한 루프 방지

        while (cur != null && depth < MAX_DEPTH) {
            if (cur instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                log.debug("Restored interrupt flag due to InterruptedException in cause chain");
                return;
            }
            cur = cur.getCause();
            depth++;
        }
    }
}
