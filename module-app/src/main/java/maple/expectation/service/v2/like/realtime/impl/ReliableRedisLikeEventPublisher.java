package maple.expectation.service.v2.like.realtime.impl;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.queue.RedisKey;
import maple.expectation.service.v2.like.realtime.LikeEventPublisher;
import maple.expectation.service.v2.like.realtime.dto.LikeEvent;
import org.redisson.api.RReliableTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;

/**
 * Redis RReliableTopic 기반 좋아요 이벤트 발행자 (at-least-once)
 *
 * <h3>Issue #278 P0: Scale-out 환경 실시간 좋아요 동기화</h3>
 *
 * <p>Redisson RReliableTopic을 사용하여 인스턴스 간 이벤트 Fanout
 *
 * <p>RTopic(at-most-once)과 달리 인스턴스 재시작 시에도 메시지 유실 방지
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Purple (Data): L1 eviction은 idempotent → 중복 수신 무해
 *   <li>Purple (Data): Hash Tag {likes} 호환성 문제 없음 (RReliableTopic 내부 키도 동일 슬롯)
 *   <li>Red (SRE): 메트릭으로 발행 성공/실패 + transport 태그로 구분
 *   <li>Green (Performance): countSubscribers() Gauge로 구독자 수 모니터링
 * </ul>
 *
 * <h3>RTopic과의 차이</h3>
 *
 * <ul>
 *   <li>RTopic: Redis Pub/Sub → at-most-once, 수신자 없으면 유실
 *   <li>RReliableTopic: Redis Stream 기반 → at-least-once, watchdog 지원
 * </ul>
 *
 * <h3>CLAUDE.md Section 12: LogicExecutor 패턴</h3>
 *
 * <p>모든 Redis 작업은 executeOrDefault로 Graceful Degradation
 *
 * @see RedisLikeEventPublisher RTopic 기반 구현체 (기본값)
 */
@Slf4j
@RequiredArgsConstructor
public class ReliableRedisLikeEventPublisher implements LikeEventPublisher {

  private final RedissonClient redissonClient;
  private final LogicExecutor executor;
  private final MeterRegistry meterRegistry;

  @Value("${app.instance-id:${HOSTNAME:unknown}}")
  private String instanceId;

  /**
   * Phase D: 구독자 수 Gauge 등록
   *
   * <p>countSubscribers() 호출로 현재 RReliableTopic 구독자 수를 모니터링
   */
  @PostConstruct
  public void registerMetrics() {
    meterRegistry.gauge(
        "like.reliable.topic.subscribers",
        this,
        pub -> {
          RReliableTopic t =
              redissonClient.getReliableTopic(RedisKey.LIKE_EVENTS_RELIABLE_TOPIC.getKey());
          return t.countSubscribers();
        });
  }

  /**
   * 좋아요 이벤트 발행 (at-least-once)
   *
   * <p>Redis Stream 장애 시에도 좋아요 기능은 정상 동작 (Graceful Degradation)
   */
  @Override
  public void publish(LikeEvent event) {
    TaskContext context = TaskContext.of("LikeReliablePubSub", "Publish", event.userIgn());

    long clientsReceived = executor.executeOrDefault(() -> doPublish(event), 0L, context);

    if (clientsReceived > 0) {
      recordPublishSuccess();
      log.debug(
          "[ReliableLikeEventPublisher] Event published: userIgn={}, delta={}, clients={}",
          event.userIgn(),
          event.newDelta(),
          clientsReceived);
    } else {
      recordPublishFailure();
      log.warn(
          "[ReliableLikeEventPublisher] Event publish failed or no subscribers: userIgn={}",
          event.userIgn());
    }
  }

  /** RReliableTopic 발행 (내부 메서드) */
  private long doPublish(LikeEvent event) {
    RReliableTopic topic =
        redissonClient.getReliableTopic(RedisKey.LIKE_EVENTS_RELIABLE_TOPIC.getKey());
    return topic.publish(event);
  }

  @Override
  public void publishLike(String userIgn, long newDelta) {
    publish(LikeEvent.like(userIgn, newDelta, instanceId));
  }

  @Override
  public void publishUnlike(String userIgn, long newDelta) {
    publish(LikeEvent.unlike(userIgn, newDelta, instanceId));
  }

  // ==================== Metrics ====================

  private void recordPublishSuccess() {
    meterRegistry
        .counter("like.event.publish", "status", "success", "transport", "reliable-topic")
        .increment();
  }

  private void recordPublishFailure() {
    meterRegistry
        .counter("like.event.publish", "status", "failure", "transport", "reliable-topic")
        .increment();
  }
}
