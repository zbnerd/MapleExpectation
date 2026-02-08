package maple.expectation.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.cache.TieredCacheManager;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.service.v2.like.realtime.LikeEventPublisher;
import maple.expectation.service.v2.like.realtime.LikeEventSubscriber;
import maple.expectation.service.v2.like.realtime.impl.RedisLikeEventPublisher;
import maple.expectation.service.v2.like.realtime.impl.RedisLikeEventSubscriber;
import maple.expectation.service.v2.like.realtime.impl.ReliableRedisLikeEventPublisher;
import maple.expectation.service.v2.like.realtime.impl.ReliableRedisLikeEventSubscriber;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 좋아요 실시간 동기화 설정 (Issue #278)
 *
 * <h3>Scale-out 환경 Pub/Sub 설정</h3>
 *
 * <p>like.realtime.enabled=true 시 활성화
 *
 * <p>like.realtime.transport로 RTopic / RReliableTopic 전환
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): Strategy 패턴으로 구현체 교체 가능
 *   <li>Red (SRE): ConditionalOnProperty로 런타임 비활성화 지원
 *   <li>Green (Performance): 단일 토픽 인스턴스 재사용
 *   <li>Yellow (QA): RTopic/RReliableTopic 교차 통신 불가 → Blue-Green 배포 필수
 *   <li>Purple (Data): RReliableTopic at-least-once + L1 eviction idempotent → 중복 수신 무해
 * </ul>
 *
 * <h3>Transport 전환 전략</h3>
 *
 * <pre>
 * like.realtime.transport=rtopic           → 기존 RTopic (at-most-once, 기본값)
 * like.realtime.transport=reliable-topic   → RReliableTopic (at-least-once)
 * </pre>
 *
 * @see RedisLikeEventPublisher
 * @see RedisLikeEventSubscriber
 * @see ReliableRedisLikeEventPublisher
 * @see ReliableRedisLikeEventSubscriber
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "like.realtime.enabled", havingValue = "true", matchIfMissing = true)
public class LikeRealtimeSyncConfig {

  // ==================== RTopic (기존, 기본값) ====================

  /**
   * RTopic 기반 설정 (at-most-once, 기본값)
   *
   * <p>like.realtime.transport=rtopic 또는 미설정 시 활성화
   */
  @Configuration
  @RequiredArgsConstructor
  @ConditionalOnProperty(
      name = "like.realtime.transport",
      havingValue = "rtopic",
      matchIfMissing = true)
  static class RTopicConfig {

    private final RedissonClient redissonClient;
    private final TieredCacheManager cacheManager;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;

    @Bean
    public LikeEventPublisher likeEventPublisher() {
      log.info("[LikeRealtimeSyncConfig] Creating RTopic LikeEventPublisher bean (at-most-once)");
      return new RedisLikeEventPublisher(redissonClient, executor, meterRegistry);
    }

    @Bean
    public LikeEventSubscriber likeEventSubscriber() {
      log.info("[LikeRealtimeSyncConfig] Creating RTopic LikeEventSubscriber bean (at-most-once)");
      return new RedisLikeEventSubscriber(redissonClient, cacheManager, executor, meterRegistry);
    }
  }

  // ==================== RReliableTopic (Issue #278 P0) ====================

  /**
   * RReliableTopic 기반 설정 (at-least-once)
   *
   * <p>like.realtime.transport=reliable-topic 시 활성화
   *
   * <h4>주의: Blue-Green 배포 필수</h4>
   *
   * <p>RTopic과 RReliableTopic은 Redis 구조가 다르므로 롤링 배포 시 교차 통신 불가. 전체 동시 전환 필요.
   */
  @Configuration
  @RequiredArgsConstructor
  @ConditionalOnProperty(name = "like.realtime.transport", havingValue = "reliable-topic")
  static class ReliableTopicConfig {

    private final RedissonClient redissonClient;
    private final CacheManager cacheManager;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;

    @Bean
    public LikeEventPublisher likeEventPublisher() {
      log.info(
          "[LikeRealtimeSyncConfig] Creating RReliableTopic LikeEventPublisher bean (at-least-once)");
      return new ReliableRedisLikeEventPublisher(redissonClient, executor, meterRegistry);
    }

    @Bean
    public LikeEventSubscriber likeEventSubscriber() {
      log.info(
          "[LikeRealtimeSyncConfig] Creating RReliableTopic LikeEventSubscriber bean (at-least-once)");
      return new ReliableRedisLikeEventSubscriber(
          redissonClient, cacheManager, executor, meterRegistry);
    }
  }
}
