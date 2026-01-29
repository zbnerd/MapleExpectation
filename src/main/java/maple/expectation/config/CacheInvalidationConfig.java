package maple.expectation.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.cache.TieredCacheManager;
import maple.expectation.global.cache.invalidation.CacheInvalidationPublisher;
import maple.expectation.global.cache.invalidation.CacheInvalidationSubscriber;
import maple.expectation.global.cache.invalidation.impl.RedisCacheInvalidationPublisher;
import maple.expectation.global.cache.invalidation.impl.RedisCacheInvalidationSubscriber;
import maple.expectation.global.executor.LogicExecutor;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 캐시 무효화 Pub/Sub 설정 (Issue #278: L1 Cache Coherence)
 *
 * <h3>Scale-out 환경에서 TieredCache L1 캐시 일관성 보장</h3>
 * <p>cache.invalidation.pubsub.enabled=true 시 활성화</p>
 *
 * <h3>Callback 패턴 (순환참조 방지)</h3>
 * <pre>
 * TieredCacheManager → TieredCache (callback)
 *    ↑ @PostConstruct
 * CacheInvalidationConfig → RedisCacheInvalidationPublisher
 * </pre>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Blue (Architect): 좋아요 Pub/Sub과 분리된 패키지 (SRP)</li>
 *   <li>Red (SRE): Feature Flag + NO-OP 기본값 (Graceful Degradation)</li>
 *   <li>Purple (Auditor): Callback으로 순환참조 완전 차단</li>
 *   <li>Green (Performance): fire-and-forget, 성능 영향 무시 가능</li>
 * </ul>
 *
 * @see RedisCacheInvalidationPublisher
 * @see RedisCacheInvalidationSubscriber
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "cache.invalidation.pubsub.enabled", havingValue = "true", matchIfMissing = true)
public class CacheInvalidationConfig {

    private final RedissonClient redissonClient;
    private final CacheManager cacheManager;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;
    private final String instanceId;

    /** @PostConstruct에서 재사용할 Publisher 인스턴스 (CGLIB 순환참조 방지) */
    private final RedisCacheInvalidationPublisher publisherInstance;

    public CacheInvalidationConfig(
            RedissonClient redissonClient,
            CacheManager cacheManager,
            LogicExecutor executor,
            MeterRegistry meterRegistry,
            @Value("${app.instance-id:${HOSTNAME:unknown}}") String instanceId
    ) {
        this.redissonClient = redissonClient;
        this.cacheManager = cacheManager;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.instanceId = instanceId;
        this.publisherInstance = new RedisCacheInvalidationPublisher(redissonClient, executor, meterRegistry);
    }

    /**
     * 캐시 무효화 이벤트 발행자 Bean
     */
    @Bean
    public CacheInvalidationPublisher cacheInvalidationPublisher() {
        log.info("[CacheInvalidationConfig] Creating CacheInvalidationPublisher bean");
        return publisherInstance;
    }

    /**
     * 캐시 무효화 이벤트 구독자 Bean
     *
     * <p>P0-3: TieredCacheManager 직접 주입으로 L1 캐시 접근</p>
     * <p>@PostConstruct에서 자동 구독 시작</p>
     */
    @Bean
    public CacheInvalidationSubscriber cacheInvalidationSubscriber() {
        log.info("[CacheInvalidationConfig] Creating CacheInvalidationSubscriber bean");

        // P0-3: CacheManager가 TieredCacheManager인지 확인
        if (!(cacheManager instanceof TieredCacheManager tieredManager)) {
            log.warn("[CacheInvalidationConfig] CacheManager is not TieredCacheManager, " +
                    "cache invalidation subscriber will not work properly");
            return new RedisCacheInvalidationSubscriber(
                    redissonClient, null, executor, meterRegistry);
        }

        return new RedisCacheInvalidationSubscriber(
                redissonClient, tieredManager, executor, meterRegistry);
    }

    /**
     * TieredCacheManager에 Callback 연결 (P0-2, P0-4 해결)
     *
     * <p>@PostConstruct로 Bean 초기화 후 callback 주입</p>
     * <p>순환참조 방지: publisherInstance 필드로 CGLIB 프록시 우회</p>
     */
    @PostConstruct
    public void connectInvalidationCallback() {
        if (!(cacheManager instanceof TieredCacheManager tieredManager)) {
            log.warn("[CacheInvalidationConfig] CacheManager is not TieredCacheManager, skipping callback connection");
            return;
        }

        // P0-2: instanceId 주입
        tieredManager.setInstanceId(instanceId);

        // P0-4: invalidation callback 연결 (CGLIB 우회: 필드 직접 참조)
        tieredManager.setInvalidationCallback(publisherInstance::publish);

        log.info("[CacheInvalidationConfig] Callback connected: instanceId={}", instanceId);
    }
}
