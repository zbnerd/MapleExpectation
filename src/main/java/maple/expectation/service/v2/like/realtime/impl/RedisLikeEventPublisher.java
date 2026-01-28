package maple.expectation.service.v2.like.realtime.impl;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.queue.RedisKey;
import maple.expectation.service.v2.like.realtime.LikeEventPublisher;
import maple.expectation.service.v2.like.realtime.dto.LikeEvent;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;

/**
 * Redis RTopic 기반 좋아요 이벤트 발행자
 *
 * <h3>Issue #278: Scale-out 환경 실시간 좋아요 동기화</h3>
 * <p>Redisson RTopic을 사용하여 인스턴스 간 이벤트 Fanout</p>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Green (Performance): RTopic은 Redis Pub/Sub 네이티브 래퍼 → 오버헤드 최소</li>
 *   <li>Red (SRE): 메트릭으로 발행 성공/실패 모니터링</li>
 *   <li>Purple (Data): Hash Tag {likes}로 클러스터 슬롯 보장</li>
 * </ul>
 *
 * <h3>CLAUDE.md Section 12: LogicExecutor 패턴</h3>
 * <p>모든 Redis 작업은 executeOrDefault로 Graceful Degradation</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RedisLikeEventPublisher implements LikeEventPublisher {

    private final RedissonClient redissonClient;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;

    @Value("${app.instance-id:${HOSTNAME:unknown}}")
    private String instanceId;

    /**
     * 좋아요 이벤트 발행
     *
     * <p>Redis Pub/Sub 장애 시에도 좋아요 기능은 정상 동작 (Graceful Degradation)</p>
     */
    @Override
    public void publish(LikeEvent event) {
        TaskContext context = TaskContext.of("LikePubSub", "Publish", event.userIgn());

        long clientsReceived = executor.executeOrDefault(
                () -> doPublish(event),
                0L,  // 장애 시 0 반환 (발행 실패)
                context
        );

        if (clientsReceived > 0) {
            recordPublishSuccess();
            log.debug("[LikeEventPublisher] Event published: userIgn={}, delta={}, clients={}",
                    event.userIgn(), event.newDelta(), clientsReceived);
        } else {
            recordPublishFailure();
            log.warn("[LikeEventPublisher] Event publish failed or no subscribers: userIgn={}",
                    event.userIgn());
        }
    }

    /**
     * RTopic 발행 (내부 메서드)
     */
    private long doPublish(LikeEvent event) {
        RTopic topic = redissonClient.getTopic(RedisKey.LIKE_EVENTS_TOPIC.getKey());
        return topic.publish(event);
    }

    @Override
    public void publishLike(String userIgn, long newDelta) {
        LikeEvent event = LikeEvent.like(userIgn, newDelta, instanceId);
        publish(event);
    }

    @Override
    public void publishUnlike(String userIgn, long newDelta) {
        LikeEvent event = LikeEvent.unlike(userIgn, newDelta, instanceId);
        publish(event);
    }

    // ==================== Metrics ====================

    private void recordPublishSuccess() {
        meterRegistry.counter("like.event.publish", "status", "success").increment();
    }

    private void recordPublishFailure() {
        meterRegistry.counter("like.event.publish", "status", "failure").increment();
    }
}
