package maple.expectation.global.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.event.MySQLDownEvent;
import maple.expectation.global.event.MySQLUpEvent;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Dynamic TTL Manager (Issue #218)
 *
 * <p>MySQL 장애 시 Redis 캐시 TTL을 동적으로 관리합니다.</p>
 *
 * <h4>동작 흐름</h4>
 * <ol>
 *   <li>MySQL DOWN 감지 → 분산 락 획득 → 대상 캐시 TTL 제거 (PERSIST)</li>
 *   <li>MySQL UP 감지 → 분산 락 획득 → CacheConfig 기반 TTL 복원 (EXPIRE)</li>
 * </ol>
 *
 * <h4>Stateless 설계 (P1-3)</h4>
 * <ul>
 *   <li>TTL 백업은 Redis Hash 대신 CacheConfig 설정 기반 복원</li>
 *   <li>equipment:* → 10분 (CacheConfig L2 설정)</li>
 *   <li>ocidCache:* → 60분 (CacheConfig L2 설정)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicTTLManager {

    /**
     * CacheConfig 기반 TTL 설정 (P1-3: 메모리 절약)
     *
     * <p>Redis Hash 백업 대신 설정 기반 복원으로 메모리 사용량 감소</p>
     */
    private static final Map<String, Duration> CACHE_TTL_CONFIG = Map.of(
            "equipment", Duration.ofMinutes(10),
            "ocidCache", Duration.ofMinutes(60)
    );

    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final MySQLFallbackProperties properties;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    public void init() {
        log.info("[DynamicTTL] 초기화 완료. 대상 캐시 패턴: {}", properties.getTargetCachePatterns());
    }

    /**
     * MySQL DOWN 이벤트 처리 (P0-N4: @Async + @EventListener)
     *
     * <p>분산 락을 획득한 후 대상 캐시의 TTL을 제거합니다.</p>
     */
    @Async
    @EventListener
    public void onMySQLDown(MySQLDownEvent event) {
        executor.executeVoid(() -> {
            log.warn("[DynamicTTL] MySQL DOWN 이벤트 수신: {}", event);

            RLock lock = redissonClient.getLock(properties.getTtlLockKey());
            boolean acquired = lock.tryLock(
                    properties.getLockWaitSeconds(),
                    properties.getLockLeaseSeconds(),
                    TimeUnit.SECONDS
            );

            if (!acquired) {
                log.warn("[DynamicTTL] 분산 락 획득 실패 - 다른 인스턴스가 처리 중");
                return;
            }

            executeWithLockSafety(lock, this::extendAllCacheTTL);

        }, TaskContext.of("Resilience", "OnMySQLDown", event.circuitBreakerName()));
    }

    /**
     * MySQL UP 이벤트 처리 (P0-N4: @Async + @EventListener)
     *
     * <p>분산 락을 획득한 후 대상 캐시의 TTL을 복원합니다.</p>
     */
    @Async
    @EventListener
    public void onMySQLUp(MySQLUpEvent event) {
        executor.executeVoid(() -> {
            log.info("[DynamicTTL] MySQL UP 이벤트 수신: {}", event);

            RLock lock = redissonClient.getLock(properties.getTtlLockKey());
            boolean acquired = lock.tryLock(
                    properties.getLockWaitSeconds(),
                    properties.getLockLeaseSeconds(),
                    TimeUnit.SECONDS
            );

            if (!acquired) {
                log.warn("[DynamicTTL] 분산 락 획득 실패 - 다른 인스턴스가 처리 중");
                return;
            }

            executeWithLockSafety(lock, this::restoreAllCacheTTL);

        }, TaskContext.of("Resilience", "OnMySQLUp", event.circuitBreakerName()));
    }

    /**
     * 락 안전 실행 (P1-N2: isHeldByCurrentThread 체크)
     */
    private void executeWithLockSafety(RLock lock, Runnable action) {
        executor.executeWithFinally(
                () -> {
                    action.run();
                    return null;
                },
                () -> {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                        log.debug("[DynamicTTL] 분산 락 해제 완료");
                    }
                },
                TaskContext.of("Resilience", "ExecuteWithLock", properties.getTtlLockKey())
        );
    }

    /**
     * 대상 캐시 TTL 제거 (PERSIST)
     *
     * <p>MySQL 장애 시 캐시가 만료되지 않도록 TTL을 제거합니다.</p>
     */
    private void extendAllCacheTTL() {
        int totalKeys = 0;
        for (String pattern : properties.getTargetCachePatterns()) {
            List<String> keys = scanKeys(pattern);
            log.info("[DynamicTTL] TTL 제거 대상 키 수: {} (패턴: {})", keys.size(), pattern);

            // 메트릭 기록 (P1-N4)
            meterRegistry.gauge("mysql.ttl.scan.keys",
                    Tags.of("action", "persist", "pattern", extractCacheName(pattern)),
                    keys.size());

            if (keys.isEmpty()) {
                continue;
            }

            totalKeys += keys.size();
            // RBatch로 비동기 일괄 처리 (P0-1: Fire-and-Forget)
            executePersistBatch(keys);
        }

        meterRegistry.counter("mysql.ttl.extended").increment(totalKeys);
        log.info("[DynamicTTL] 모든 대상 캐시 TTL 제거 완료: {} 키", totalKeys);
    }

    /**
     * 대상 캐시 TTL 복원 (EXPIRE)
     *
     * <p>MySQL 복구 시 CacheConfig 기반으로 TTL을 복원합니다.</p>
     */
    private void restoreAllCacheTTL() {
        int totalKeys = 0;
        for (String pattern : properties.getTargetCachePatterns()) {
            List<String> keys = scanKeys(pattern);
            log.info("[DynamicTTL] TTL 복원 대상 키 수: {} (패턴: {})", keys.size(), pattern);

            // 메트릭 기록 (P1-N4)
            String cacheName = extractCacheName(pattern);
            meterRegistry.gauge("mysql.ttl.scan.keys",
                    Tags.of("action", "restore", "pattern", cacheName),
                    keys.size());

            if (keys.isEmpty()) {
                continue;
            }

            totalKeys += keys.size();
            Duration ttl = CACHE_TTL_CONFIG.getOrDefault(cacheName, Duration.ofMinutes(15));

            // RBatch로 비동기 일괄 처리
            executeExpireBatch(keys, ttl);
        }

        meterRegistry.counter("mysql.ttl.restored").increment(totalKeys);
        log.info("[DynamicTTL] 모든 대상 캐시 TTL 복원 완료: {} 키", totalKeys);
    }

    /**
     * SCAN 명령으로 키 조회 (P0-N2: COUNT 1000 필수)
     *
     * <p>KEYS 명령 대신 SCAN을 사용하여 Redis 블로킹을 방지합니다.</p>
     */
    private List<String> scanKeys(String pattern) {
        List<String> keys = new ArrayList<>();

        redisTemplate.execute((RedisCallback<Void>) connection -> {
            ScanOptions options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(properties.getScanCount())
                    .build();

            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next()));
                }
            }
            return null;
        });

        return keys;
    }

    /**
     * RBatch PERSIST 실행 (TTL 제거)
     *
     * <p>P0-N3: Double Failure (Redis 장애) 시 Graceful Degradation</p>
     */
    private void executePersistBatch(List<String> keys) {
        RBatch batch = redissonClient.createBatch(BatchOptions.defaults());

        for (String key : keys) {
            batch.getBucket(key, StringCodec.INSTANCE).remainTimeToLiveAsync();
            // PERSIST 명령 (TTL 제거)
            batch.getKeys().clearExpireAsync(key);
        }

        // 비동기 실행 (Fire-and-Forget 패턴)
        batch.executeAsync()
                .thenAccept(results -> {
                    log.debug("[DynamicTTL] PERSIST 배치 완료: {} 키", keys.size());
                    meterRegistry.counter("mysql.ttl.batch.success", Tags.of("action", "persist"))
                            .increment();
                })
                .exceptionally(ex -> {
                    log.error("[DynamicTTL] PERSIST 배치 실패 (P0-N3: Double Failure 가능성)", ex);
                    meterRegistry.counter("mysql.ttl.batch.failures", Tags.of("action", "persist"))
                            .increment(keys.size());
                    meterRegistry.counter("mysql.double_failure.count").increment();
                    return null;
                });
    }

    /**
     * RBatch EXPIRE 실행 (TTL 복원)
     *
     * <p>P0-N3: Double Failure (Redis 장애) 시 Graceful Degradation</p>
     */
    private void executeExpireBatch(List<String> keys, Duration ttl) {
        RBatch batch = redissonClient.createBatch(BatchOptions.defaults());

        for (String key : keys) {
            batch.getBucket(key, StringCodec.INSTANCE).expireAsync(ttl);
        }

        // 비동기 실행
        batch.executeAsync()
                .thenAccept(results -> {
                    log.debug("[DynamicTTL] EXPIRE 배치 완료: {} 키, TTL: {}", keys.size(), ttl);
                    meterRegistry.counter("mysql.ttl.batch.success", Tags.of("action", "restore"))
                            .increment();
                })
                .exceptionally(ex -> {
                    log.error("[DynamicTTL] EXPIRE 배치 실패 (P0-N3: Double Failure 가능성)", ex);
                    meterRegistry.counter("mysql.ttl.batch.failures", Tags.of("action", "restore"))
                            .increment(keys.size());
                    return null;
                });
    }

    /**
     * 패턴에서 캐시 이름 추출
     *
     * @param pattern "equipment:*" → "equipment"
     */
    private String extractCacheName(String pattern) {
        int colonIndex = pattern.indexOf(':');
        return colonIndex > 0 ? pattern.substring(0, colonIndex) : pattern.replace("*", "");
    }
}
