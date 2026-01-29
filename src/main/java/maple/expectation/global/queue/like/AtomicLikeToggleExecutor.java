package maple.expectation.global.queue.like;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.queue.RedisKey;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lua Script 기반 원자적 좋아요 토글 실행기
 *
 * <h3>Issue #285: P0-1/P0-2/P0-3 해결</h3>
 * <p>SISMEMBER + SADD/SREM + HINCRBY를 단일 Lua Script로 통합하여
 * TOCTOU Race Condition과 비원자적 이중 쓰기 문제를 원천 차단합니다.</p>
 *
 * <h3>원자성 보장</h3>
 * <ul>
 *   <li>Check + Act이 단일 Redis 명령으로 실행 (race window 제거)</li>
 *   <li>relation SET + pending SET + counter HASH 동시 변경</li>
 *   <li>Hash Tag {@code {likes}}로 Cluster 슬롯 동일 보장</li>
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Purple (Auditor): TOCTOU 원천 차단, Financial-Grade 원자성</li>
 *   <li>Green (Performance): 3-4 RTT -> 1 RTT (Lua Script)</li>
 *   <li>Red (SRE): 단일 연산으로 부분 실패 불가</li>
 *   <li>Blue (Architect): SRP - 원자적 토글만 담당</li>
 * </ul>
 *
 * @see RedisKey#LIKE_RELATIONS 관계 SET 키
 * @see RedisKey#LIKE_RELATIONS_PENDING 대기열 SET 키
 * @see RedisKey#LIKE_BUFFER 카운터 HASH 키
 */
@Slf4j
public class AtomicLikeToggleExecutor {

    /**
     * Atomic Toggle Lua Script
     *
     * <p>KEYS[1] = {likes}:relations (SET)
     * KEYS[2] = {likes}:relations:pending (SET)
     * KEYS[3] = {likes}:buffer (HASH)</p>
     *
     * <p>ARGV[1] = relationKey (fingerprint:targetOcid)
     * ARGV[2] = userIgn (HASH field for counter)</p>
     *
     * <p>Returns: {action, newDelta}
     * action: 1 = LIKED, -1 = UNLIKED
     * newDelta: counter HASH의 새 값</p>
     */
    private static final String LUA_ATOMIC_TOGGLE = """
            -- Atomic Like Toggle: Check + Act in single operation
            -- Prevents TOCTOU race condition (P0-1)
            -- Ensures relation + counter atomicity (P0-2, P0-3)
            local relations_key = KEYS[1]
            local pending_key   = KEYS[2]
            local buffer_key    = KEYS[3]
            local relation_val  = ARGV[1]
            local user_ign      = ARGV[2]

            -- Check current state
            local exists = redis.call('SISMEMBER', relations_key, relation_val)

            if exists == 1 then
                -- Currently liked -> UNLIKE (remove + decrement)
                redis.call('SREM', relations_key, relation_val)
                redis.call('SREM', pending_key, relation_val)
                local new_delta = redis.call('HINCRBY', buffer_key, user_ign, -1)
                return {-1, new_delta}
            else
                -- Not liked -> LIKE (add + increment)
                redis.call('SADD', relations_key, relation_val)
                redis.call('SADD', pending_key, relation_val)
                local new_delta = redis.call('HINCRBY', buffer_key, user_ign, 1)
                return {1, new_delta}
            end
            """;

    /**
     * Atomic Like (Always Add) Lua Script - DB fallback 확인 후 사용
     *
     * <p>DB에서 이미 좋아요 여부 확인 완료 상태에서 사용.
     * 강제로 LIKE 실행 (SADD + HINCRBY +1)</p>
     */
    private static final String LUA_ATOMIC_LIKE = """
            local relations_key = KEYS[1]
            local pending_key   = KEYS[2]
            local buffer_key    = KEYS[3]
            local relation_val  = ARGV[1]
            local user_ign      = ARGV[2]

            local is_new = redis.call('SADD', relations_key, relation_val)
            if is_new == 1 then
                redis.call('SADD', pending_key, relation_val)
            end
            local new_delta = redis.call('HINCRBY', buffer_key, user_ign, 1)
            return {1, new_delta}
            """;

    /**
     * Atomic Unlike (Always Remove) Lua Script - DB fallback 확인 후 사용
     */
    private static final String LUA_ATOMIC_UNLIKE = """
            local relations_key = KEYS[1]
            local pending_key   = KEYS[2]
            local buffer_key    = KEYS[3]
            local relation_val  = ARGV[1]
            local user_ign      = ARGV[2]

            redis.call('SREM', relations_key, relation_val)
            redis.call('SREM', pending_key, relation_val)
            local new_delta = redis.call('HINCRBY', buffer_key, user_ign, -1)
            return {-1, new_delta}
            """;

    private final RedissonClient redissonClient;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;

    private final String relationsKey;
    private final String pendingKey;
    private final String bufferKey;

    /** Lua Script SHA 캐싱 */
    private final AtomicReference<String> toggleSha = new AtomicReference<>();

    public AtomicLikeToggleExecutor(
            RedissonClient redissonClient,
            LogicExecutor executor,
            MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.relationsKey = RedisKey.LIKE_RELATIONS.getKey();
        this.pendingKey = RedisKey.LIKE_RELATIONS_PENDING.getKey();
        this.bufferKey = RedisKey.LIKE_BUFFER.getKey();

        log.info("[AtomicLikeToggle] Initialized with keys: {}, {}, {}",
                relationsKey, pendingKey, bufferKey);
    }

    /**
     * 원자적 좋아요 토글 실행
     *
     * <p>단일 Lua Script로 CHECK + ACT을 원자적으로 수행합니다.
     * Race condition이 구조적으로 불가능합니다.</p>
     *
     * @param fingerprint 좋아요를 누른 계정의 fingerprint
     * @param targetOcid  대상 캐릭터의 OCID
     * @param userIgn     대상 캐릭터 닉네임 (카운터 키)
     * @return 토글 결과 (liked, newDelta), Redis 장애 시 null
     */
    public ToggleResult toggle(String fingerprint, String targetOcid, String userIgn) {
        String relationKey = fingerprint + ":" + targetOcid;
        TaskContext context = TaskContext.of("LikeToggle", "Atomic", userIgn);

        return executor.executeOrDefault(
                () -> doToggle(relationKey, userIgn),
                null,
                context
        );
    }

    private ToggleResult doToggle(String relationKey, String userIgn) {
        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        String sha = toggleSha.get();

        List<Long> result = executor.executeOrCatch(
                () -> evalToggleWithCachedSha(script, sha, relationKey, userIgn),
                e -> evalToggleWithReloadedSha(script, relationKey, userIgn),
                TaskContext.of("LikeToggle", "EvalScript", userIgn)
        );

        long action = result.get(0);
        long newDelta = result.get(1);
        boolean liked = action == 1;

        recordToggleMetrics(liked);
        log.debug("[AtomicLikeToggle] {}: relation={}, delta={}",
                liked ? "LIKED" : "UNLIKED", relationKey, newDelta);

        return new ToggleResult(liked, newDelta);
    }

    @SuppressWarnings("unchecked")
    private List<Long> evalToggleWithCachedSha(
            RScript script, String sha, String relationKey, String userIgn) {
        if (sha == null) {
            throw new IllegalStateException("SHA not cached");
        }
        return script.evalSha(
                RScript.Mode.READ_WRITE,
                sha,
                RScript.ReturnType.MULTI,
                List.of(relationsKey, pendingKey, bufferKey),
                relationKey, userIgn
        );
    }

    @SuppressWarnings("unchecked")
    private List<Long> evalToggleWithReloadedSha(
            RScript script, String relationKey, String userIgn) {
        String sha = script.scriptLoad(LUA_ATOMIC_TOGGLE);
        toggleSha.set(sha);
        return script.evalSha(
                RScript.Mode.READ_WRITE,
                sha,
                RScript.ReturnType.MULTI,
                List.of(relationsKey, pendingKey, bufferKey),
                relationKey, userIgn
        );
    }

    // ==================== Metrics ====================

    private void recordToggleMetrics(boolean liked) {
        String action = liked ? "like" : "unlike";
        meterRegistry.counter("like.atomic.toggle", "action", action).increment();
    }

    /**
     * 원자적 토글 결과
     *
     * @param liked    토글 후 좋아요 상태 (true: 좋아요됨, false: 취소됨)
     * @param newDelta 카운터 버퍼의 새 delta 값
     */
    public record ToggleResult(boolean liked, long newDelta) {}
}
