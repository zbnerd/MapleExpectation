package maple.expectation.global.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.function.ThrowingFunction;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.springframework.stereotype.Component;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultLogicExecutor implements LogicExecutor {

    private final ExceptionTranslator translator;

    @Override
    public <T> T execute(ThrowingSupplier<T> task, TaskContext context) {
        String taskName = context.toTaskName();
        long startTime = System.currentTimeMillis();
        try {
            log.debug("🚀 [Task:Start] {}", taskName);
            T result = task.get();
            log.debug("✅ [Task:Success] {} | Duration: {}ms", taskName, System.currentTimeMillis() - startTime);
            return result;
        } catch (Throwable e) {
            log.error("❌ [Task:Failed] {} | Error: {}", taskName, e.getMessage());
            throw translator.translate(e, context);
        }
    }

    @Override
    public <T> T executeWithRecovery(ThrowingSupplier<T> task, Function<Throwable, T> recovery, TaskContext context) {
        try {
            return execute(task, context);
        } catch (Throwable e) {
            log.warn("🔄 [Task:Recovered] Executing recovery for {}", context.toTaskName());
            return recovery.apply(e);
        }
    }

    @Override
    public <T> T executeOrDefault(ThrowingSupplier<T> task, T defaultValue, TaskContext context) {
        return executeWithRecovery(task, e -> defaultValue, context);
    }

    @Override
    public void executeVoid(ThrowingRunnable task, TaskContext context) {
        execute(() -> { task.run(); return null; }, context);
    }

    @Override
    public <T> T executeWithFinally(ThrowingSupplier<T> task, Runnable finallyBlock, TaskContext context) {
        try {
            return execute(task, context);
        } finally {
            log.debug("🧹 [Task:Finally] Cleaning up for {}", context.toTaskName());
            finallyBlock.run();
        }
    }

    @Override
    public <T> T executeWithTranslation(ThrowingSupplier<T> task, ExceptionTranslator translator, TaskContext context) {
        try {
            return task.get();
        } catch (Throwable e) {
            throw translator.translate(e, context);
        }
    }

    @Override
    public <T> T executeCheckedWithRecovery(
            ThrowingSupplier<T> task,
            ThrowingFunction<Throwable, T> recovery,
            TaskContext context) throws Throwable {

        String taskName = context.toTaskName();
        try {
            log.debug("🚀 [Task:CheckedStart] {}", taskName);
            return task.get(); // 직접 호출하여 Throwable 유지
        } catch (Throwable e) {
            log.warn("🔄 [Task:Recovered] Executing checked recovery for {}", taskName);
            return recovery.apply(e); // 복구 로직에서도 Throwable 전파 허용
        }
    }

    @Override
    public <T> T executeWithFallback(ThrowingSupplier<T> task, Function<Throwable, T> fallback, TaskContext context) {
        String taskName = context.toTaskName();
        try {
            log.debug("🚀 [Task:FallbackStart] {}", taskName);
            return task.get(); // 1. 우선 시도 (체크 예외 포함)
        } catch (Throwable e) {
            // 2. 실패 시 로깅 후 Fallback 수행
            log.warn("🔄 [Task:Fallback] Attempting fallback for {} | Reason: {}", taskName, e.getMessage());
            return fallback.apply(e); // 예외를 소화하여 결과값으로 전환
        }
    }
}