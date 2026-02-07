package maple.expectation.service.v2.like.realtime.impl;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.queue.RedisKey;
import maple.expectation.service.v2.like.realtime.LikeEventSubscriber;
import maple.expectation.service.v2.like.realtime.dto.LikeEvent;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Redis RTopic 기반 좋아요 이벤트 구독자
 *
 * <h3>Issue #278: Scale-out 환경 실시간 좋아요 동기화</h3>
 *
 * <p>다른 인스턴스에서 발행한 이벤트를 수신하여 L1(Caffeine) 캐시 무효화
 *
 * <h3>캐시 무효화 전략 (5-Agent Council 합의)</h3>
 *
 * <ul>
 *   <li>Pub/Sub 수신 → L1 즉시 evict
 *   <li>TTL(5분)은 Fallback용 (Pub/Sub 유실 시)
 *   <li>자기 자신이 발행한 이벤트는 무시 (Self-skip)
 * </ul>
 *
 * <h3>CLAUDE.md Section 12: LogicExecutor 패턴</h3>
 *
 * <p>모든 캐시 작업은 executeVoid로 예외 처리
 */
@Slf4j
@RequiredArgsConstructor
public class RedisLikeEventSubscriber implements LikeEventSubscriber {

  private final RedissonClient redissonClient;
  private final CacheManager cacheManager;
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
    TaskContext context = TaskContext.of("LikePubSub", "Subscribe", instanceId);

    executor.executeVoid(
        () -> {
          topic = redissonClient.getTopic(RedisKey.LIKE_EVENTS_TOPIC.getKey());

          // MessageListener 등록
          listenerId = topic.addListener(LikeEvent.class, createMessageListener());

          log.info(
              "[LikeEventSubscriber] Subscribed to topic: {}, instanceId={}",
              RedisKey.LIKE_EVENTS_TOPIC.getKey(),
              instanceId);
        },
        context);
  }

  /** 메시지 리스너 생성 (CLAUDE.md Section 15: 람다 3줄 이내) */
  private MessageListener<LikeEvent> createMessageListener() {
    return (channel, event) -> onEvent(event);
  }

  /**
   * 이벤트 수신 및 처리
   *
   * <p>P0-3 대응: Self-skip으로 불필요한 캐시 무효화 방지
   */
  @Override
  public void onEvent(LikeEvent event) {
    // Self-skip: 자기가 발행한 이벤트는 무시
    if (instanceId.equals(event.sourceInstanceId())) {
      log.trace("[LikeEventSubscriber] Self-skip: userIgn={}", event.userIgn());
      return;
    }

    TaskContext context = TaskContext.of("LikePubSub", "OnEvent", event.userIgn());

    executor.executeVoid(
        () -> {
          evictL1Cache(event.userIgn());
          recordEventReceived();
          log.debug(
              "[LikeEventSubscriber] L1 cache evicted: userIgn={}, source={}, delta={}",
              event.userIgn(),
              event.sourceInstanceId(),
              event.newDelta());
        },
        context);
  }

  /**
   * L1 캐시 무효화 (character 캐시)
   *
   * <p>TieredCacheManager.getL1CacheDirect()로 L1만 직접 evict
   *
   * <p>L2(Redis)는 모든 인스턴스가 공유하므로 evict 불필요
   */
  private void evictL1Cache(String userIgn) {
    // character 캐시의 L1만 evict (GameCharacter 엔티티)
    Cache characterCache = cacheManager.getCache("character");
    if (characterCache != null) {
      characterCache.evict(userIgn);
    }

    // characterBasic 캐시도 evict (기본 정보)
    Cache basicCache = cacheManager.getCache("characterBasic");
    if (basicCache != null) {
      basicCache.evict(userIgn);
    }
  }

  /** 구독 해제 (애플리케이션 종료 시) */
  @Override
  @PreDestroy
  public void unsubscribe() {
    TaskContext context = TaskContext.of("LikePubSub", "Unsubscribe", instanceId);

    executor.executeVoid(
        () -> {
          if (topic != null && listenerId != null) {
            topic.removeListener(listenerId);
            log.info("[LikeEventSubscriber] Unsubscribed from topic: instanceId={}", instanceId);
          }
        },
        context);
  }

  // ==================== Metrics ====================

  private void recordEventReceived() {
    meterRegistry.counter("like.event.received").increment();
  }
}
