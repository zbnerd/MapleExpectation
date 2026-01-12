package maple.expectation.service.v2.like.strategy;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import maple.expectation.service.v2.like.dto.FetchResult;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * RENAME 기반 원자적 fetch 전략 (Fallback)
 *
 * <p>Lua Script 미지원 환경 또는 장애 시 사용되는 Fallback 전략.
 * 기존 LikeSyncService 로직과 동일한 방식으로 동작합니다.</p>
 *
 * <p>금융수준 안전 설계:
 * <ul>
 *   <li>RENAME으로 원본 키를 임시 키로 이동</li>
 *   <li>EXPIRE로 임시 키 TTL 설정 (안전장치)</li>
 *   <li>HGETALL로 데이터 조회</li>
 * </ul>
 * </p>
 *
 * <p>제한사항:
 * <ul>
 *   <li>RENAME + EXPIRE + HGETALL이 별도 명령어로 실행됨 (비원자적)</li>
 *   <li>Lua Script 전략보다 Race Condition 위험이 약간 높음</li>
 * </ul>
 * </p>
 *
 * @since 2.0.0
 */
@Slf4j
public class RenameAtomicFetchStrategy implements AtomicFetchStrategy {

    private static final String STRATEGY_NAME = "rename";

    private final StringRedisTemplate redisTemplate;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;
    private final Duration tempKeyTtl;

    public RenameAtomicFetchStrategy(StringRedisTemplate redisTemplate,
                                     LogicExecutor executor,
                                     MeterRegistry meterRegistry,
                                     int tempKeyTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.tempKeyTtl = Duration.ofSeconds(tempKeyTtlSeconds);
    }

    /**
     * fetchAndMove 구현 (LSP 준수)
     *
     * <p>금융수준 안전 설계 (CRITICAL FIX - LSP 준수):
     * <ul>
     *   <li>LuaScriptAtomicFetchStrategy와 동일한 예외 처리 정책</li>
     *   <li>예외 발생 시 AtomicFetchException으로 변환 후 상위 전파</li>
     *   <li>클라이언트는 전략 구현체에 관계없이 동일한 예외 처리 가능</li>
     * </ul>
     * </p>
     */
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
                () -> redisTemplate.delete(tempKey),
                TaskContext.of("AtomicFetch", "deleteTempKey", tempKey)
        );
    }

    @Override
    public String strategyName() {
        return STRATEGY_NAME;
    }

    // ========== Private Methods ==========

    private FetchResult executeFetchAndMove(String sourceKey, String tempKey) {
        Boolean exists = redisTemplate.hasKey(sourceKey);
        if (!Boolean.TRUE.equals(exists)) {
            return FetchResult.empty();
        }

        // RENAME (원본 → 임시)
        redisTemplate.rename(sourceKey, tempKey);

        // TTL 설정 (안전장치)
        redisTemplate.expire(tempKey, tempKeyTtl);

        // HGETALL
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(tempKey);
        Map<String, Long> data = convertToTypedMap(entries);

        if (!data.isEmpty()) {
            log.debug("Rename fetch completed: sourceKey={}, tempKey={}, entries={}",
                    sourceKey, tempKey, data.size());
        }

        return new FetchResult(tempKey, data);
    }

    private void executeRestore(String tempKey, String sourceKey) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(tempKey);
        if (entries.isEmpty()) {
            return;
        }

        var ops = redisTemplate.opsForHash();
        entries.forEach((key, value) -> {
            String userIgn = String.valueOf(key);
            long count = parseLongSafe(value);
            ops.increment(sourceKey, userIgn, count);
        });

        redisTemplate.delete(tempKey);

        log.warn("Compensation executed: tempKey={} -> sourceKey={}, restoredEntries={}",
                tempKey, sourceKey, entries.size());
    }

    private Map<String, Long> convertToTypedMap(Map<Object, Object> entries) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Long> result = new HashMap<>(entries.size());
        entries.forEach((key, value) -> {
            String k = String.valueOf(key);
            long v = parseLongSafe(value);
            result.put(k, v);
        });
        return result;
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
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                log.warn("Malformed Redis data ignored: value={}", s);
                recordParseFailure();
                return 0L;
            }
        }
        log.warn("Unexpected Redis data type: class={}, value={}", value.getClass().getSimpleName(), value);
        recordParseFailure();
        return 0L;
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
