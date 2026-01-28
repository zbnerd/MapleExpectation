package maple.expectation.global.queue.like;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.queue.RedisKey;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import maple.expectation.service.v2.cache.LikeBufferStrategy;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis 기반 좋아요 카운터 버퍼 (#271 V5 Stateless Architecture)
 *
 * <h3>역할</h3>
 * <p>Redis HASH를 사용하여 좋아요 증분(delta)을 버퍼링합니다.
 * Scale-out 환경에서도 단일 진실 소스(Single Source of Truth)를 보장합니다.</p>
 *
 * <h3>기존 LikeBufferStorage 대비 개선</h3>
 * <ul>
 *   <li>기존: Caffeine Cache (In-Memory) → 인스턴스별 분산</li>
 *   <li>개선: Redis HASH (Distributed) → 전역 일관성</li>
 * </ul>
 *
 * <h3>Redis 구조</h3>
 * <pre>
 * {likes}:buffer (HASH)
 * ├── userIgn1 → delta (HINCRBY)
 * ├── userIgn2 → delta
 * └── ...
 * </pre>
 *
 * <h3>원자적 연산</h3>
 * <ul>
 *   <li>HINCRBY: 원자적 증분 (동시성 안전)</li>
 *   <li>HSCAN + HDEL: Lua Script로 원자적 fetch + clear</li>
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Blue (Architect): 전략 패턴으로 In-Memory/Redis 교체 가능</li>
 *   <li>Green (Performance): HINCRBY O(1) 복잡도</li>
 *   <li>Red (SRE): 인스턴스 장애 시에도 데이터 보존</li>
 *   <li>Purple (Auditor): Lua Script 원자성으로 데이터 유실 방지</li>
 * </ul>
 *
 * @see RedisKey#LIKE_BUFFER Redis 키 정의
 */
@Slf4j
public class RedisLikeBufferStorage implements LikeBufferStrategy {

    private static final String LUA_FETCH_AND_CLEAR = """
            -- Fetch all entries and delete them atomically
            -- Returns: [[field1, value1], [field2, value2], ...]
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])

            local cursor = '0'
            local results = {}
            local fields_to_delete = {}
            local count = 0

            -- HSCAN to get entries (limit으로 제한)
            repeat
                local scan_result = redis.call('HSCAN', key, cursor, 'COUNT', 100)
                cursor = scan_result[1]
                local entries = scan_result[2]

                for i = 1, #entries, 2 do
                    if count >= limit then
                        break
                    end
                    local field = entries[i]
                    local value = entries[i + 1]
                    table.insert(results, {field, value})
                    table.insert(fields_to_delete, field)
                    count = count + 1
                end
            until cursor == '0' or count >= limit

            -- Delete fetched fields
            if #fields_to_delete > 0 then
                redis.call('HDEL', key, unpack(fields_to_delete))
            end

            return results
            """;

    private final RedissonClient redissonClient;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;
    private final String bufferKey;

    /** Lua Script SHA 캐싱 */
    private final AtomicReference<String> fetchAndClearSha = new AtomicReference<>();

    public RedisLikeBufferStorage(
            RedissonClient redissonClient,
            LogicExecutor executor,
            MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.bufferKey = RedisKey.LIKE_BUFFER.getKey();

        registerMetrics();
        log.info("[RedisLikeBufferStorage] Initialized with key: {}", bufferKey);
    }

    private void registerMetrics() {
        // 버퍼 내 대기 중인 카운터 수
        Gauge.builder("like.buffer.redis.entries", this, storage -> getBufferSize())
                .description("Redis 버퍼의 미반영 좋아요 엔트리 수")
                .register(meterRegistry);

        // 버퍼 내 총 delta 합계
        Gauge.builder("like.buffer.redis.total_delta", this, storage -> getTotalDelta())
                .description("Redis 버퍼의 미반영 좋아요 총합")
                .register(meterRegistry);
    }

    /**
     * 좋아요 증분 (원자적)
     *
     * <p>Redis HINCRBY를 사용하여 동시 요청에도 안전하게 증분합니다.</p>
     *
     * @param userIgn 대상 사용자 IGN
     * @param delta   증분값 (양수: 좋아요, 음수: 취소)
     * @return 증분 후 값, 실패 시 null
     */
    public Long increment(String userIgn, long delta) {
        return executor.executeOrDefault(
                () -> {
                    RMap<String, Long> buffer = getBuffer();
                    Long newValue = buffer.addAndGet(userIgn, delta);

                    meterRegistry.counter("like.buffer.increment", "ign", userIgn).increment();
                    log.debug("[LikeBuffer] Increment: {} += {} → {}", userIgn, delta, newValue);

                    return newValue;
                },
                null,
                TaskContext.of("LikeBuffer", "Increment", userIgn)
        );
    }

    /**
     * 현재 카운터 조회
     *
     * @param userIgn 대상 사용자 IGN
     * @return 현재 delta 값, 없으면 0, Redis 장애 시 null
     */
    public Long get(String userIgn) {
        return executor.executeOrDefault(
                () -> {
                    Long value = getBuffer().get(userIgn);
                    return value != null ? value : 0L;
                },
                null,
                TaskContext.of("LikeBuffer", "Get", userIgn)
        );
    }

    /**
     * 모든 카운터 조회 (읽기 전용)
     *
     * @return userIgn → delta 맵, 실패 시 빈 맵
     */
    public Map<String, Long> getAllCounters() {
        return executor.executeOrDefault(
                () -> new HashMap<>(getBuffer().readAllMap()),
                Collections.emptyMap(),
                TaskContext.of("LikeBuffer", "GetAll")
        );
    }

    /**
     * 원자적 fetch + clear (Flush용)
     *
     * <p>Lua Script로 HSCAN + HDEL을 원자적으로 수행합니다.
     * 이는 데이터 유실 없이 버퍼를 비우는 것을 보장합니다.</p>
     *
     * @param limit 최대 조회 개수
     * @return userIgn → delta 맵
     */
    public Map<String, Long> fetchAndClear(int limit) {
        return executor.executeOrDefault(
                () -> doFetchAndClear(limit),
                Collections.emptyMap(),
                TaskContext.of("LikeBuffer", "FetchAndClear")
        );
    }

    /**
     * Lua Script 실행 (Section 12 준수: try-catch → executeOrCatch)
     */
    @SuppressWarnings("unchecked")
    private Map<String, Long> doFetchAndClear(int limit) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        String sha = fetchAndClearSha.get();

        // Section 12: try-catch → executeOrCatch
        List<List<String>> rawResult = executor.executeOrCatch(
                () -> evalWithCachedSha(script, sha, limit),
                e -> evalWithReloadedSha(script, limit),
                TaskContext.of("LikeBuffer", "EvalScript")
        );

        return parseRawResult(rawResult);
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> evalWithCachedSha(RScript script, String sha, int limit) {
        if (sha == null) {
            throw new IllegalStateException("SHA not cached");
        }
        return script.evalSha(
                RScript.Mode.READ_WRITE,
                sha,
                RScript.ReturnType.MULTI,
                List.of(bufferKey),
                String.valueOf(limit)
        );
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> evalWithReloadedSha(RScript script, int limit) {
        String sha = script.scriptLoad(LUA_FETCH_AND_CLEAR);
        fetchAndClearSha.set(sha);
        return script.evalSha(
                RScript.Mode.READ_WRITE,
                sha,
                RScript.ReturnType.MULTI,
                List.of(bufferKey),
                String.valueOf(limit)
        );
    }

    private Map<String, Long> parseRawResult(List<List<String>> rawResult) {
        Map<String, Long> result = new HashMap<>();
        for (List<String> entry : rawResult) {
            if (entry.size() >= 2) {
                String field = entry.get(0);
                Long value = Long.parseLong(entry.get(1));
                result.put(field, value);
            }
        }

        if (!result.isEmpty()) {
            meterRegistry.counter("like.buffer.flush.entries").increment(result.size());
            log.info("[LikeBuffer] FetchAndClear: {} entries", result.size());
        }

        return result;
    }

    /**
     * 특정 사용자의 카운터 삭제
     *
     * @param userIgn 대상 사용자 IGN
     * @return 삭제된 값, 없으면 null
     */
    public Long remove(String userIgn) {
        return executor.executeOrDefault(
                () -> getBuffer().remove(userIgn),
                null,
                TaskContext.of("LikeBuffer", "Remove", userIgn)
        );
    }

    /**
     * 버퍼 크기 조회
     *
     * @return 버퍼 내 엔트리 수
     */
    public int getBufferSize() {
        return executor.executeOrDefault(
                () -> getBuffer().size(),
                0,
                TaskContext.of("LikeBuffer", "Size")
        );
    }

    /**
     * 총 delta 합계 조회 (메트릭용)
     */
    private long getTotalDelta() {
        return executor.executeOrDefault(
                () -> getBuffer().readAllValues().stream()
                        .mapToLong(Long::longValue)
                        .sum(),
                0L,
                TaskContext.of("LikeBuffer", "TotalDelta")
        );
    }

    /**
     * Redis HASH 버퍼 접근 (LongCodec 사용)
     */
    private RMap<String, Long> getBuffer() {
        return redissonClient.getMap(bufferKey, LongCodec.INSTANCE);
    }

    /**
     * 버퍼 키 조회 (테스트용)
     */
    public String getBufferKey() {
        return bufferKey;
    }

    @Override
    public StrategyType getType() {
        return StrategyType.REDIS;
    }
}
