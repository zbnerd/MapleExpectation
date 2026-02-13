package maple.expectation.service.v2.like.realtime.impl;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.queue.RedisKey;
import maple.expectation.service.v2.like.realtime.LikeEventSubscriber;
import maple.expectation.service.v2.like.realtime.dto.LikeEvent;
import org.redisson.api.RReliableTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Redis RReliableTopic 기반 좋아요 이벤트 구독자 (at-least-once)
 *
 * <h3>Issue #278 P0: Scale-out 환경 실시간 좋아요 동기화</h3>
 *
 * <p>RReliableTopic: Redis Stream 기반으로 인스턴스 재시작 시에도 메시지 유실 방지
 *
 * <h3>캐시 무효화 전략 (5-Agent Council 합의)</h3>
 *
 * <ul>
 *   <li>Pub/Sub 수신 → L1 즉시 evict
 *   <li>TTL(5분)은 Fallback용 (Pub/Sub 유실 시)
 *   <li>자기 자신이 발행한 이벤트는 무시 (Self-skip)
 *   <li>RReliableTopic at-least-once → L1 eviction idempotent → 중복 수신 무해 (Purple 확인)
 * </ul>
 *
 * <h3>RTopic과의 차이</h3>
 *
 * <ul>
 *   <li>listenerId 타입: RTopic은 int, RReliableTopic은 String
 *   <li>removeListener: RReliableTopic은 listenerId(String)로 해제
 * </ul>
 *
 * <h3>Phase D: 모니터링</h3>
 *
 * <ul>
 *   <li>Gauge: like.reliable.subscriber.active (1.0 = subscribed, 0.0 = not)
 * </ul>
 *
 * <h3>CLAUDE.md Section 12: LogicExecutor 패턴</h3>
 *
 * <p>모든 캐시/Redis 작업은 executeVoid로 예외 처리
 *
 * @see RedisLikeEventSubscriber RTopic 기반 구현체 (기본값)
 */
@Slf4j
@RequiredArgsConstructor
public class ReliableRedisLikeEventSubscriber implements LikeEventSubscriber {

  private final RedissonClient redissonClient;
  private final CacheManager cacheManager;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;

  @Value("${app.instance-id:${HOSTNAME:unknown}}")
  private String instanceId;

  /** 구독 해제용 리스너 ID (RReliableTopic은 String 반환) */
  private volatile String listenerId;

  /** RReliableTopic 인스턴스 (구독 해제용) */
  private volatile RReliableTopic topic;

  /** 구독 상태 추적 (Phase D: Gauge 모니터링) */
  private final AtomicBoolean subscribed = new AtomicBoolean(false);

  /** 이벤트 구독 시작 (애플리케이션 시작 시) */
  @Override
  @PostConstruct
  public void subscribe() {
    TaskContext context = TaskContext.of("LikeReliablePubSub", "Subscribe", instanceId);

    executor.executeVoid(
        () -> {
          topic = redissonClient.getReliableTopic(RedisKey.LIKE_EVENTS_RELIABLE_TOPIC.getKey());

          // RReliableTopic.addListener() → String listenerId
          listenerId = topic.addListener(LikeEvent.class, createMessageListener());
          subscribed.set(true);

          // Phase D: 구독 상태 Gauge 등록
          registerSubscriberGauge();

          log.info(
              "[ReliableLikeEventSubscriber] Subscribed to reliable topic: {}, instanceId={}",
              RedisKey.LIKE_EVENTS_RELIABLE_TOPIC.getKey(),
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
   * <p>Self-skip: 자기가 발행한 이벤트는 무시
   *
   * <p>at-least-once: 중복 수신 가능하지만 L1 eviction은 idempotent
   */
  @Override
  public void onEvent(LikeEvent event) {
    // Self-skip: 자기가 발행한 이벤트는 무시
    if (instanceId.equals(event.sourceInstanceId())) {
      log.trace("[ReliableLikeEventSubscriber] Self-skip: userIgn={}", event.userIgn());
      return;
    }

    TaskContext context = TaskContext.of("LikeReliablePubSub", "OnEvent", event.userIgn());

    executor.executeVoid(
        () -> {
          evictL1Cache(event.userIgn());
          recordEventReceived();
          log.debug(
              "[ReliableLikeEventSubscriber] L1 cache evicted: userIgn={}, source={}, delta={}",
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
    Cache characterCache = cacheManager.getCache("character");
    if (characterCache != null) {
      characterCache.evict(userIgn);
    }

    Cache basicCache = cacheManager.getCache("characterBasic");
    if (basicCache != null) {
      basicCache.evict(userIgn);
    }
  }

  /**
   * 구독 해제 (애플리케이션 종료 시)
   *
   * <h4>Phase D: Graceful Shutdown 강화</h4>
   *
   * <p>@PreDestroy에서 명시적 해제 + 상태 플래그 업데이트
   */
  @Override
  @PreDestroy
  public void unsubscribe() {
    TaskContext context = TaskContext.of("LikeReliablePubSub", "Unsubscribe", instanceId);

    executor.executeVoid(
        () -> {
          if (topic != null && listenerId != null) {
            topic.removeListener(listenerId);
            subscribed.set(false);
            log.info(
                "[ReliableLikeEventSubscriber] Unsubscribed from reliable topic: instanceId={}",
                instanceId);
          }
        },
        context);
  }

  // ==================== Metrics ====================

  /** Phase D: 구독 상태 Gauge 등록 */
  private void registerSubscriberGauge() {
    meterRegistry.gauge("like.reliable.subscriber.active", subscribed, b -> b.get() ? 1.0 : 0.0);
  }

  private void recordEventReceived() {
    meterRegistry.counter("like.event.received", "transport", "reliable-topic").increment();
  }
}
