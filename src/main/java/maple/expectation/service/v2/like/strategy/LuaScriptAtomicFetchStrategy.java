package maple.expectation.service.v2.like.strategy;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import maple.expectation.service.v2.like.dto.FetchResult;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lua Script 기반 원자적 fetch 전략 (Primary)
 *
 * <p>금융수준 안전 설계 (Context7 Best Practice):
 * <ul>
 *   <li><b>원자성</b>: Redis 싱글 스레드에서 RENAME + HGETALL + EXPIRE 원자적 실행</li>
 *   <li><b>데이터 보존</b>: 임시 키에 데이터 보존 → JVM 크래시 시 복구 가능</li>
 *   <li><b>TTL 안전장치</b>: 1시간 후 자동 만료 → 영구 메모리 누수 방지</li>
 *   <li><b>Cluster 호환</b>: Hash Tag 패턴으로 같은 슬롯 보장</li>
 * </ul>
 * </p>
 *
 * @since 2.0.0
 */
@Slf4j
public class LuaScriptAtomicFetchStrategy implements AtomicFetchStrategy {

    private static final String STRATEGY_NAME = "lua";

    private final RedissonClient redissonClient;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;
    private final int tempKeyTtlSeconds;

    public LuaScriptAtomicFetchStrategy(RedissonClient redissonClient,
                                        LogicExecutor executor,
                                        MeterRegistry meterRegistry,
                                        int tempKeyTtlSeconds) {
        this.redissonClient = redissonClient;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.tempKeyTtlSeconds = tempKeyTtlSeconds;
    }

    /**
     * Lua Script: 원자적 RENAME + EXPIRE + HGETALL
     *
     * <p>금융수준 안전 설계:
     * <ul>
     *   <li>KEYS[1] = 원본 키 ({buffer:likes})</li>
     *   <li>KEYS[2] = 임시 키 ({buffer:likes}:sync:{uuid})</li>
     *   <li>RENAME으로 새 데이터가 원본 키에 안전하게 축적</li>
     *   <li>EXPIRE로 임시 키 TTL 설정 (안전장치)</li>
     *   <li>HGETALL로 데이터 반환</li>
     * </ul>
     * </p>
     */
    private static final String LUA_ATOMIC_MOVE = """
            local exists = redis.call('EXISTS', KEYS[1])
            if exists == 0 then
                return {}
            end
            redis.call('RENAME', KEYS[1], KEYS[2])
            redis.call('EXPIRE', KEYS[2], ARGV[1])
            return redis.call('HGETALL', KEYS[2])
            """;

    /**
     * Lua Script: 임시 키 → 원본 키 복원 (보상 트랜잭션)
     */
    private static final String LUA_RESTORE = """
            local data = redis.call('HGETALL', KEYS[1])
            if #data == 0 then
                return 0
            end
            for i = 1, #data, 2 do
                redis.call('HINCRBY', KEYS[2], data[i], data[i+1])
            end
            redis.call('DEL', KEYS[1])
            return #data / 2
            """;

    @Override
    public FetchResult fetchAndMove(String sourceKey, String tempKey) {
        return executor.executeOrCatch(
                () -> {
                    FetchResult result = executeFetchAndMove(sourceKey, tempKey);
                    recordFetchMetric(result.isEmpty() ? "empty" : "success");
                    return result;
                },
                e -> {
                    recordFetchMetric("failure");
                    throw ExceptionTranslator.forRedisScript().translate(e,
                            TaskContext.of("AtomicFetch", "fetchAndMove", sourceKey));
                },
                TaskContext.of("AtomicFetch", "fetchAndMove", sourceKey)
        );
    }

    /**
     * 임시 키 데이터를 원본 키로 복원 (보상 트랜잭션)
     *
     * <p>금융수준 방어적 프로그래밍:
     * tempKey가 null이면 조기 리턴 (FetchResult.empty() 케이스)</p>
     */
    @Override
    public void restore(String tempKey, String sourceKey) {
        if (tempKey == null || tempKey.isBlank()) {
            log.debug("Restore skipped: tempKey is null or blank");
            return;
        }
        executor.executeVoid(
                () -> executeRestore(tempKey, sourceKey),
                TaskContext.of("AtomicFetch", "restore", tempKey)
        );
    }

    /**
     * 임시 키 삭제 (커밋 시 호출)
     *
     * <p>금융수준 방어적 프로그래밍:
     * tempKey가 null이면 조기 리턴</p>
     */
    @Override
    public void deleteTempKey(String tempKey) {
        if (tempKey == null || tempKey.isBlank()) {
            log.debug("Delete skipped: tempKey is null or blank");
            return;
        }
        executor.executeVoid(
                () -> redissonClient.getKeys().delete(tempKey),
                TaskContext.of("AtomicFetch", "deleteTempKey", tempKey)
        );
    }

    @Override
    public String strategyName() {
        return STRATEGY_NAME;
    }

    // ========== Private Methods (3-Line Rule 준수) ==========

    private FetchResult executeFetchAndMove(String sourceKey, String tempKey) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);

        List<Object> result = script.eval(
                RScript.Mode.READ_WRITE,
                LUA_ATOMIC_MOVE,
                RScript.ReturnType.MULTI,
                Arrays.asList(sourceKey, tempKey),
                String.valueOf(tempKeyTtlSeconds)
        );

        Map<String, Long> data = parseHgetallResult(result);

        if (!data.isEmpty()) {
            log.debug("Atomic fetch completed: sourceKey={}, tempKey={}, entries={}",
                    sourceKey, tempKey, data.size());
        }

        return new FetchResult(tempKey, data);
    }

    private void executeRestore(String tempKey, String sourceKey) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);

        Long restoredCount = script.eval(
                RScript.Mode.READ_WRITE,
                LUA_RESTORE,
                RScript.ReturnType.INTEGER,
                Arrays.asList(tempKey, sourceKey)
        );

        log.warn("Compensation executed: tempKey={} -> sourceKey={}, restoredEntries={}",
                tempKey, sourceKey, restoredCount);
    }

    /**
     * HGETALL 결과 파싱 (key1, val1, key2, val2, ... → Map)
     */
    private Map<String, Long> parseHgetallResult(List<Object> result) {
        if (result == null || result.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Long> map = new HashMap<>(result.size() / 2);
        for (int i = 0; i < result.size() - 1; i += 2) {
            String key = String.valueOf(result.get(i));
            long value = parseLongSafe(result.get(i + 1));
            map.put(key, value);
        }
        return map;
    }

    /**
     * 안전한 Long 파싱 (P1 최적화: TaskContext 오버헤드 제거)
     *
     * <p>CRITICAL FIX: 외부 데이터(Redis)는 절대 신뢰하지 않음.
     * 잘못된 형식의 데이터가 있어도 전체 동기화가 실패하면 안 됨.</p>
     *
     * <p>성능 최적화:
     * <ul>
     *   <li>루프 내 호출되므로 TaskContext 생성 오버헤드 제거</li>
     *   <li>Pattern Matching (Java 17) 활용으로 타입 체크 최적화</li>
     *   <li>실패 시 로그 + 기본값 반환 (예외 전파 X)</li>
     * </ul>
     * </p>
     *
     * @param value Redis에서 반환된 값
     * @return 파싱된 Long 값, 실패 시 0L
     */
    private long parseLongSafe(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            return parseStringToLong(s);
        }
        log.warn("Unexpected Redis data type: class={}, value={}", value.getClass().getSimpleName(), value);
        recordParseFailure();
        return 0L;
    }

    /**
     * 문자열 Long 파싱 (CLAUDE.md Section 12 준수: 선검증 후 파싱)
     */
    private long parseStringToLong(String s) {
        if (s == null || s.isBlank()) {
            recordParseFailure();
            return 0L;
        }
        // 선검증: 숫자 형식 확인 (try-catch 회피)
        if (isValidLongFormat(s)) {
            return Long.parseLong(s);
        }
        log.warn("Malformed Redis data ignored: value={}", s);
        recordParseFailure();
        return 0L;
    }

    private boolean isValidLongFormat(String s) {
        if (s.isEmpty()) return false;
        int start = (s.charAt(0) == '-') ? 1 : 0;
        if (start == s.length()) return false;
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /**
     * 파싱 실패 메트릭 기록 (데이터 품질 모니터링)
     */
    private void recordParseFailure() {
        meterRegistry.counter("cache.atomic.parse.failure", "strategy", STRATEGY_NAME).increment();
    }

    // ========== Metrics (Micrometer) ==========

    /**
     * Atomic Fetch 메트릭 기록
     *
     * @param result success | failure | empty
     */
    private void recordFetchMetric(String result) {
        meterRegistry.counter("cache.atomic.fetch",
                "strategy", STRATEGY_NAME,
                "result", result
        ).increment();
    }
}
