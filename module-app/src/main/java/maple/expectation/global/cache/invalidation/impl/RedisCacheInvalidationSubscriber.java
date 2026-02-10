package maple.expectation.global.cache.invalidation.impl;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.cache.TieredCacheManager;
import maple.expectation.global.cache.invalidation.CacheInvalidationEvent;
import maple.expectation.global.cache.invalidation.CacheInvalidationSubscriber;
import maple.expectation.global.cache.invalidation.InvalidationType;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.queue.RedisKey;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;

/**
 * Redis RTopic 기반 캐시 무효화 이벤트 구독자
 *
 * <h3>Issue #278: Scale-out 환경 L1 Cache Coherence</h3>
 *
 * <p>다른 인스턴스에서 발행한 이벤트를 수신하여 L1(Caffeine) 캐시 무효화
 *
 * <h3>P0-3 반영: TieredCacheManager 직접 주입</h3>
 *
 * <p>getL1CacheDirect()로 L1 캐시만 직접 접근 (L2 evict 불필요)
 *
 * <h3>캐시 무효화 전략 (5-Agent Council 합의)</h3>
 *
 * <ul>
 *   <li>EVICT: 특정 키의 L1 캐시만 무효화
 *   <li>CLEAR_ALL: 해당 캐시의 L1 전체 무효화
 *   <li>Self-skip: 자기 자신이 발행한 이벤트는 무시
 *   <li>TTL(5분): Pub/Sub 유실 시 Fallback
 * </ul>
 *
 * <h3>CLAUDE.md Section 12: LogicExecutor 패턴</h3>
 *
 * <p>모든 캐시 작업은 executeVoid로 예외 처리
 */
@Slf4j
@RequiredArgsConstructor
public class RedisCacheInvalidationSubscriber implements CacheInvalidationSubscriber {

  private final RedissonClient redissonClient;
  private final TieredCacheManager tieredCacheManager;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;

  @Value("${app.instance-id:${HOSTNAME:unknown}}")
  private String instanceId;

  /** 구독 해제용 리스너 ID */
  private volatile Integer listenerId;

  /** RTopic 인스턴스 (구독 해제용) */
  private volatile RTopic topic;

  /** 이벤트 구독 시작 (애플리케이션 시작 시) */
  @Override
  @PostConstruct
  public void subscribe() {
    TaskContext context = TaskContext.of("CacheInvalidation", "Subscribe", instanceId);

    executor.executeVoid(
        () -> {
          topic = redissonClient.getTopic(RedisKey.CACHE_INVALIDATION_TOPIC.getKey());
          listenerId = topic.addListener(CacheInvalidationEvent.class, createMessageListener());

          log.info(
              "[CacheInvalidation] Subscribed to topic: {}, instanceId={}",
              RedisKey.CACHE_INVALIDATION_TOPIC.getKey(),
              instanceId);
        },
        context);
  }

  /** 메시지 리스너 생성 (CLAUDE.md Section 15: 람다 3줄 이내) */
  private MessageListener<CacheInvalidationEvent> createMessageListener() {
    return (channel, event) -> onEvent(event);
  }

  /**
   * 이벤트 수신 및 처리
   *
   * <p>Purple(Auditor) 합의: Self-skip으로 무한루프 방지
   */
  @Override
  public void onEvent(CacheInvalidationEvent event) {
    // Self-skip: 자기가 발행한 이벤트는 무시
    if (instanceId.equals(event.sourceInstanceId())) {
      log.trace(
          "[CacheInvalidation] Self-skip: cache={}, type={}", event.cacheName(), event.type());
      return;
    }

    TaskContext context = TaskContext.of("CacheInvalidation", "OnEvent", event.cacheName());

    executor.executeVoid(
        () -> {
          invalidateL1Cache(event);
          recordEventReceived(event.type());
        },
        context);
  }

  /**
   * L1 캐시 무효화 (P0-3: TieredCacheManager.getL1CacheDirect() 사용)
   *
   * <p>L2(Redis)는 모든 인스턴스가 공유하므로 evict 불필요. L1(Caffeine)만 직접 무효화하여 Cache Coherence 보장.
   */
  private void invalidateL1Cache(CacheInvalidationEvent event) {
    if (tieredCacheManager == null) {
      log.warn("[CacheInvalidation] TieredCacheManager is null, skipping L1 invalidation");
      return;
    }

    Cache l1Cache = tieredCacheManager.getL1CacheDirect(event.cacheName());
    if (l1Cache == null) {
      log.debug("[CacheInvalidation] L1 cache not found: {}", event.cacheName());
      return;
    }

    switch (event.type()) {
      case EVICT -> {
        l1Cache.evict(event.key());
        log.debug(
            "[CacheInvalidation] L1 evicted: cache={}, key={}, source={}",
            event.cacheName(),
            event.key(),
            event.sourceInstanceId());
      }
      case CLEAR_ALL -> {
        l1Cache.clear();
        log.debug(
            "[CacheInvalidation] L1 cleared: cache={}, source={}",
            event.cacheName(),
            event.sourceInstanceId());
      }
    }
  }

  /** 구독 해제 (애플리케이션 종료 시) */
  @Override
  @PreDestroy
  public void unsubscribe() {
    TaskContext context = TaskContext.of("CacheInvalidation", "Unsubscribe", instanceId);

    executor.executeVoid(
        () -> {
          if (topic != null && listenerId != null) {
            topic.removeListener(listenerId);
            log.info("[CacheInvalidation] Unsubscribed from topic: instanceId={}", instanceId);
          }
        },
        context);
  }

  // ==================== Metrics ====================

  private void recordEventReceived(InvalidationType type) {
    meterRegistry.counter("cache.invalidation.received", "type", type.name()).increment();
  }
}
