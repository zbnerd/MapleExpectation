package maple.expectation.global.cache.invalidation.impl;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.cache.invalidation.CacheInvalidationEvent;
import maple.expectation.global.cache.invalidation.CacheInvalidationPublisher;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.queue.RedisKey;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

/**
 * Redis RTopic 기반 캐시 무효화 이벤트 발행자
 *
 * <h3>Issue #278: Scale-out 환경 L1 Cache Coherence</h3>
 * <p>Redisson RTopic을 사용하여 인스턴스 간 캐시 무효화 이벤트 Fanout</p>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Green (Performance): fire-and-forget, evict당 1-3ms 추가 (무시 가능)</li>
 *   <li>Red (SRE): Graceful Degradation (Redis 장애 시 TTL fallback)</li>
 *   <li>Purple (Data): Hash Tag {cache}로 좋아요 토픽과 분리</li>
 * </ul>
 *
 * <h3>CLAUDE.md Section 12: LogicExecutor 패턴</h3>
 * <p>모든 Redis 작업은 executeOrDefault로 Graceful Degradation</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RedisCacheInvalidationPublisher implements CacheInvalidationPublisher {

    private final RedissonClient redissonClient;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;

    /**
     * 캐시 무효화 이벤트 발행
     *
     * <p>Redis Pub/Sub 장애 시에도 캐시 기능은 정상 동작 (TTL fallback)</p>
     */
    @Override
    public void publish(CacheInvalidationEvent event) {
        TaskContext context = TaskContext.of("CacheInvalidation", "Publish", event.cacheName());

        long clientsReceived = executor.executeOrDefault(
                () -> doPublish(event),
                0L,
                context
        );

        recordPublishResult(clientsReceived, event);
    }

    /**
     * RTopic 발행 (CLAUDE.md Section 15: 메서드 추출)
     */
    private long doPublish(CacheInvalidationEvent event) {
        RTopic topic = redissonClient.getTopic(RedisKey.CACHE_INVALIDATION_TOPIC.getKey());
        return topic.publish(event);
    }

    /**
     * 발행 결과 메트릭 및 로그 기록
     */
    private void recordPublishResult(long clientsReceived, CacheInvalidationEvent event) {
        if (clientsReceived > 0) {
            meterRegistry.counter("cache.invalidation.publish", "status", "success").increment();
            log.debug("[CacheInvalidation] Published: cache={}, type={}, key={}, clients={}",
                    event.cacheName(), event.type(), event.key(), clientsReceived);
        } else {
            meterRegistry.counter("cache.invalidation.publish", "status", "failure").increment();
            log.warn("[CacheInvalidation] Publish failed or no subscribers: cache={}, type={}",
                    event.cacheName(), event.type());
        }
    }
}
