package maple.expectation.global.lock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Redis Lock Fallback 메트릭 (Issue #310 Phase 2)
 *
 * <h3>목적</h3>
 * <p>Redis 장애 시 MySQL Fallback 동작을 모니터링하여 시스템 회복탄력성을 가시화합니다.</p>
 *
 * <h3>메트릭 정의</h3>
 * <ul>
 *   <li>lock.redis.failure.total: Redis 락 실패 횟수</li>
 *   <li>lock.mysql.fallback.total: MySQL Fallback 활성화 횟수</li>
 *   <li>lock.mysql.fallback.latency: Fallback 지연 시간</li>
 *   <li>lock.redis.unavailable.total: MySQL Fallback 불가능한 경우</li>
 * </ul>
 *
 * <h3>Prometheus Alert 예시</h3>
 * <pre>
 * - alert: RedisLockFallbackRateHigh
 *   expr: rate(lock.mysql.fallback.total[5m]) > 0.1
 *   for: 2m
 *   labels:
 *     severity: warning
 *   annotations:
 *     summary: "Redis lock fallback rate > 10% - investigate Redis health"
 * </pre>
 *
 * @since 2026-02-06
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LockFallbackMetrics {

    private final MeterRegistry registry;

    // Counters
    private Counter redisFailureCounter;
    private Counter mysqlFallbackCounter;
    private Counter mysqlUnavailableCounter;

    // Timer
    private Timer fallbackLatencyTimer;

    @PostConstruct
    public void init() {
        redisFailureCounter = registry.counter("lock.redis.failure.total",
                Tags.of("layer", "tiered_lock"));

        mysqlFallbackCounter = registry.counter("lock.mysql.fallback.total",
                Tags.of("layer", "tiered_lock"));

        mysqlUnavailableCounter = registry.counter("lock.redis.unavailable.total",
                Tags.of("layer", "tiered_lock"));

        fallbackLatencyTimer = registry.timer("lock.mysql.fallback.latency",
                Tags.of("layer", "tiered_lock"));

        log.info("[LockFallbackMetrics] Initialized - fallback tracking enabled");
    }

    /**
     * Redis 락 실패 기록
     *
     * @param lockKey 락 키
     * @param reason 실패 원인 (예: RedisException, CallNotPermittedException)
     * @param circuitBreakerState 서킷 브레이커 상태
     */
    public void recordRedisFailure(String lockKey, String reason, String circuitBreakerState) {
        redisFailureCounter.increment();

        // 상세 메트릭 (원인별)
        registry.counter("lock.redis.failure.detail",
                Tags.of(
                        "reason", sanitizeReason(reason),
                        "cb_state", circuitBreakerState
                )
        ).increment();

        log.warn("[LockFallback] Redis failure recorded - key={}, reason={}, state={}",
                lockKey, reason, circuitBreakerState);
    }

    /**
     * MySQL Fallback 활성화 기록
     *
     * @param lockKey 락 키
     * @param circuitBreakerState 서킷 브레이커 상태
     */
    public void recordMysqlFallback(String lockKey, String circuitBreakerState) {
        mysqlFallbackCounter.increment();

        // 상태별 메트릭
        registry.counter("lock.mysql.fallback.detail",
                Tags.of("cb_state", circuitBreakerState)
        ).increment();

        log.warn("[LockFallback] MySQL fallback activated - key={}, state={}",
                lockKey, circuitBreakerState);
    }

    /**
     * Fallback 지연 시간 기록
     *
     * @param operation 작업 유형 (executeWithLock, tryLock, orderedExecute)
     * @param durationMillis 소요 시간
     */
    public void recordFallbackLatency(String operation, long durationMillis) {
        fallbackLatencyTimer.record(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS);

        log.debug("[LockFallback] Fallback latency - op={}, duration={}ms",
                operation, durationMillis);
    }

    /**
     * MySQL Fallback 불가능 상황 기록
     *
     * <p>Redis-only 모드에서 Redis 실패 시 락 획득 불가</p>
     *
     * @param lockKey 락 키
     * @param reason 불가능한 이유
     */
    public void recordMysqlUnavailable(String lockKey, String reason) {
        mysqlUnavailableCounter.increment();

        log.error("[LockFallback] MySQL unavailable - key={}, reason={}",
                lockKey, reason);
    }

    /**
     * 메트릭 태그용 원인 정규화
     */
    private String sanitizeReason(String reason) {
        if (reason == null) {
            return "unknown";
        }
        // 예외 클래스 이름만 추출
        int lastDot = reason.lastIndexOf('.');
        return lastDot > 0 ? reason.substring(lastDot + 1) : reason;
    }
}
