package maple.expectation.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.service.v2.like.realtime.LikeEventPublisher;
import maple.expectation.service.v2.like.realtime.LikeEventSubscriber;
import maple.expectation.service.v2.like.realtime.impl.RedisLikeEventPublisher;
import maple.expectation.service.v2.like.realtime.impl.RedisLikeEventSubscriber;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 좋아요 실시간 동기화 설정 (Issue #278)
 *
 * <h3>Scale-out 환경 Pub/Sub 설정</h3>
 * <p>like.realtime.enabled=true 시 활성화</p>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Blue (Architect): Strategy 패턴으로 구현체 교체 가능</li>
 *   <li>Red (SRE): ConditionalOnProperty로 런타임 비활성화 지원</li>
 *   <li>Green (Performance): 단일 RTopic 인스턴스 재사용</li>
 * </ul>
 *
 * @see RedisLikeEventPublisher
 * @see RedisLikeEventSubscriber
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "like.realtime.enabled", havingValue = "true", matchIfMissing = true)
public class LikeRealtimeSyncConfig {

    private final RedissonClient redissonClient;
    private final CacheManager cacheManager;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;

    /**
     * 좋아요 이벤트 발행자 Bean
     */
    @Bean
    public LikeEventPublisher likeEventPublisher() {
        log.info("[LikeRealtimeSyncConfig] Creating LikeEventPublisher bean");
        return new RedisLikeEventPublisher(redissonClient, executor, meterRegistry);
    }

    /**
     * 좋아요 이벤트 구독자 Bean
     *
     * <p>@PostConstruct에서 자동 구독 시작</p>
     */
    @Bean
    public LikeEventSubscriber likeEventSubscriber() {
        log.info("[LikeRealtimeSyncConfig] Creating LikeEventSubscriber bean");
        return new RedisLikeEventSubscriber(redissonClient, cacheManager, executor, meterRegistry);
    }
}
