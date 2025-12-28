package maple.expectation.global.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import java.util.concurrent.Callable;

@RequiredArgsConstructor
public class TieredCache implements Cache {
    private final Cache l1; // Caffeine (Local)
    private final Cache l2; // Redis (Distributed)

    @Override
    public String getName() { return l2.getName(); }
    @Override
    public Object getNativeCache() { return l2.getNativeCache(); }

    @Override
    public ValueWrapper get(Object key) {
        // 1. L1(로컬) 확인
        ValueWrapper wrapper = l1.get(key);
        if (wrapper != null) return wrapper;

        // 2. L1 미스 시 L2(Redis) 확인
        wrapper = l2.get(key);
        if (wrapper != null) {
            l1.put(key, wrapper.get()); // Backfill: Redis 데이터를 로컬에 채움
        }
        return wrapper;
    }

    @Override
    public void put(Object key, Object value) {
        l1.put(key, value);
        l2.put(key, value);
    }

    @Override
    public void evict(Object key) {
        l1.evict(key);
        l2.evict(key);
    }

    @Override
    public void clear() {
        l1.clear();
        l2.clear();
    }

    // (기타 get<T>, get(key, Callable) 메서드들도 동일한 L1->L2 로직 적용)
    @Override
    public <T> T get(Object key, Class<T> type) {
        T value = l1.get(key, type);
        if (value != null) return value;
        value = l2.get(key, type);
        if (value != null) l1.put(key, value);
        return value;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) return (T) wrapper.get();
        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
    }
}