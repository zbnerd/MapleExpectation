package maple.expectation.global.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.common.function.ThrowingSupplier;
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
}