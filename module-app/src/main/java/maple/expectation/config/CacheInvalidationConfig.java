package maple.expectation.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.cache.TieredCacheManager;
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationPublisher;
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationSubscriber;
import maple.expectation.infrastructure.cache.invalidation.impl.RedisCacheInvalidationPublisher;
import maple.expectation.infrastructure.cache.invalidation.impl.RedisCacheInvalidationSubscriber;
import maple.expectation.infrastructure.executor.LogicExecutor;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 캐시 무효화 Pub/Sub 설정 (Issue #278: L1 Cache Coherence)
 *
 * <h3>Scale-out 환경에서 TieredCache L1 캐시 일관성 보장</h3>
 *
 * <p>cache.invalidation.pubsub.enabled=true 시 활성화
 *
 * <h3>P1-6: @Setter → CAS 초기화 메서드 사용</h3>
 *
 * <p>TieredCacheManager.initializeInstanceId() / initializeInvalidationCallback()
 *
 * <h3>P1-9: SmartInitializingSingleton으로 초기화 순서 보장</h3>
 *
 * <p>모든 Singleton Bean 생성 완료 후 콜백 연결로 pre-created TieredCache 인스턴스 문제 해결
 *
 * <h3>Callback 패턴 (순환참조 방지)</h3>
 *
 * <pre>
 * TieredCacheManager → TieredCache (Supplier callback)
 *    ↑ SmartInitializingSingleton.afterSingletonsInstantiated()
 * CacheInvalidationConfig → RedisCacheInvalidationPublisher
 * </pre>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
    name = "cache.invalidation.pubsub.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CacheInvalidationConfig implements SmartInitializingSingleton {

  private final RedissonClient redissonClient;
  private final CacheManager cacheManager;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;
  private final String instanceId;

  /**
   * @PostConstruct에서 재사용할 Publisher 인스턴스 (CGLIB 순환참조 방지)
   */
  private final RedisCacheInvalidationPublisher publisherInstance;

  public CacheInvalidationConfig(
      RedissonClient redissonClient,
      CacheManager cacheManager,
      LogicExecutor executor,
      MeterRegistry meterRegistry,
      @Value("${app.instance-id:${HOSTNAME:unknown}}") String instanceId) {
    this.redissonClient = redissonClient;
    this.cacheManager = cacheManager;
    this.executor = executor;
    this.meterRegistry = meterRegistry;
    this.instanceId = instanceId;
    this.publisherInstance =
        new RedisCacheInvalidationPublisher(redissonClient, executor, meterRegistry);
  }

  /** 캐시 무효화 이벤트 발행자 Bean */
  @Bean
  public CacheInvalidationPublisher cacheInvalidationPublisher() {
    log.info("[CacheInvalidationConfig] Creating CacheInvalidationPublisher bean");
    return publisherInstance;
  }

  /**
   * 캐시 무효화 이벤트 구독자 Bean
   *
   * <p>P0-3: TieredCacheManager 직접 주입으로 L1 캐시 접근
   *
   * <p>@PostConstruct에서 자동 구독 시작
   */
  @Bean
  public CacheInvalidationSubscriber cacheInvalidationSubscriber() {
    log.info("[CacheInvalidationConfig] Creating CacheInvalidationSubscriber bean");

    // P0-3: CacheManager가 TieredCacheManager인지 확인
    if (!(cacheManager instanceof TieredCacheManager tieredManager)) {
      log.warn(
          "[CacheInvalidationConfig] CacheManager is not TieredCacheManager, "
              + "cache invalidation subscriber will not work properly");
      return new RedisCacheInvalidationSubscriber(redissonClient, null, executor, meterRegistry);
    }

    return new RedisCacheInvalidationSubscriber(
        redissonClient, tieredManager, executor, meterRegistry);
  }

  /**
   * TieredCacheManager에 Callback 연결 (P0-2, P0-4 해결)
   *
   * <h4>P1-6: CAS 초기화 메서드 사용</h4>
   *
   * <p>@Setter → initializeInstanceId() / initializeInvalidationCallback()
   *
   * <h4>P1-9: SmartInitializingSingleton으로 초기화 순서 보장</h4>
   *
   * <p>모든 Singleton Bean 생성 완료 후 실행되므로, 이미 생성된 TieredCache 인스턴스도 AtomicReference를 통해 최신 instanceId와
   * callback을 참조 (Supplier-based Lazy Resolution)
   *
   * <p>중복 호출 시 CAS로 안전하게 무시
   */
  @Override
  public void afterSingletonsInstantiated() {
    if (!(cacheManager instanceof TieredCacheManager tieredManager)) {
      log.warn(
          "[CacheInvalidationConfig] CacheManager is not TieredCacheManager, skipping callback connection");
      return;
    }

    // P1-6: CAS 초기화 (중복 호출 방지)
    tieredManager.initializeInstanceId(instanceId);
    tieredManager.initializeInvalidationCallback(publisherInstance::publish);

    log.info("[CacheInvalidationConfig] Callback connected: instanceId={}", instanceId);
  }
}
