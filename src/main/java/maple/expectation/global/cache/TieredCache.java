package maple.expectation.global.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

import java.util.concurrent.Callable;

/**
 * 2층 구조 캐시 (L1: Caffeine, L2: Redis)
 * [변경점] LogicExecutor 적용으로 try-catch 박멸 및 관측성 확보
 */
@Slf4j
@RequiredArgsConstructor
public class TieredCache implements Cache {
    private final Cache l1; // Caffeine (Local)
    private final Cache l2; // Redis (Distributed)
    private final LogicExecutor executor; // ✅ LogicExecutor 주입

    @Override
    public String getName() { return l2.getName(); }
    @Override
    public Object getNativeCache() { return l2.getNativeCache(); }

    @Override
    public ValueWrapper get(Object key) {
        TaskContext context = TaskContext.of("Cache", "Get", key.toString());

        // [패턴 1] execute를 사용하여 캐시 조회 과정 모니터링
        return executor.execute(() -> {
            // 1. L1 확인
            ValueWrapper wrapper = l1.get(key);
            if (wrapper != null) return wrapper;

            // 2. L2 확인 및 Backfill
            wrapper = l2.get(key);
            if (wrapper != null) l1.put(key, wrapper.get());

            return wrapper;
        }, context);
    }

    @Override
    public void put(Object key, Object value) {
        executor.executeVoid(() -> {
            l1.put(key, value);
            l2.put(key, value);
        }, TaskContext.of("Cache", "Put", key.toString()));
    }

    @Override
    public void evict(Object key) {
        executor.executeVoid(() -> {
            l1.evict(key);
            l2.evict(key);
        }, TaskContext.of("Cache", "Evict", key.toString()));
    }

    @Override
    public void clear() {
        executor.executeVoid(() -> {
            l1.clear();
            l2.clear();
        }, TaskContext.of("Cache", "Clear"));
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        return wrapper != null ? type.cast(wrapper.get()) : null;
    }

    /**
     * ✅ P0: try-catch 박멸의 핵심
     * valueLoader 실행 중 발생하는 예외를 LogicExecutor가 처리하도록 위임
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        TaskContext context = TaskContext.of("Cache", "GetWithLoader", key.toString());

        // ✅ [패턴 6] executeWithTranslation 사용
        // try-catch 없이도 ValueRetrievalException이 정확히 던져집니다.
        return executor.executeWithTranslation(
                () -> {
                    ValueWrapper wrapper = get(key);
                    if (wrapper != null) return (T) wrapper.get();

                    T value = valueLoader.call();
                    put(key, value);
                    return value;
                },
                ExceptionTranslator.forCache(key, valueLoader), // 전용 번역기 투입
                context
        );
    }
}