package maple.expectation.global.queue;

import java.util.List;

/**
 * 메시지 큐 전략 인터페이스 (OCP 원칙 - Strategy Pattern)
 *
 * <h3>V5 Stateless Architecture (#271)</h3>
 * <p>구현체 교체로 In-Memory ↔ Redis ↔ Kafka ↔ RabbitMQ 무중단 전환 가능</p>
 *
 * <h3>GPT-5 Iteration 4 반영 (CRITICAL)</h3>
 * <ul>
 *   <li>(A) ACK는 msgId 기반 - payload 직렬화 불일치 방지</li>
 *   <li>(B) Payload Store 분리 - Re-drive 시 복원 가능</li>
 *   <li>(C) Retry ZSET 메커니즘 - Delayed Retry 지원</li>
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Blue (Architect): Strategy Pattern으로 OCP 준수</li>
 *   <li>Green (Performance): Redis Pipelining, Lua Script 최적화 가능</li>
 *   <li>Yellow (QA): Testcontainers로 통합 테스트 가능</li>
 *   <li>Purple (Auditor): At-Least-Once 보장, DLQ 지원</li>
 *   <li>Red (SRE): Circuit Breaker, Disk Spool Fallback</li>
 * </ul>
 *
 * @param <T> 메시지 페이로드 타입
 * @see QueueMessage 큐 메시지 래퍼
 * @see QueueType 큐 타입 식별자
 */
public interface MessageQueueStrategy<T> {

    /**
     * 메시지 발행
     *
     * <h4>Redis 구현 시</h4>
     * <pre>
     * 1. msgId = generateId()
     * 2. HSET payload msgId payloadJson
     * 3. RPUSH mainQueue msgId
     * </pre>
     *
     * @param message 발행할 메시지 페이로드
     * @return 발급된 메시지 ID (ack/nack 시 사용)
     */
    String publish(T message);

    /**
     * 배치 소비 - INFLIGHT로 이동
     *
     * <h4>Redis 구현 시 (Lua Script)</h4>
     * <pre>
     * 1. msgId = RPOPLPUSH mainQueue inflight
     * 2. ZADD inflight_ts now msgId
     * 3. payloadJson = HGET payload msgId
     * 4. return QueueMessage(msgId, deserialize(payloadJson))
     * </pre>
     *
     * @param batchSize 최대 소비 개수
     * @return 소비된 메시지 목록 (비어있을 수 있음)
     */
    List<QueueMessage<T>> consume(int batchSize);

    /**
     * 처리 완료 - 완전 정리
     *
     * <h4>GPT-5 Iteration 4 (A): msgId 기반 ACK</h4>
     * <p>payload 직렬화 불일치로 인한 LREM 실패 방지</p>
     *
     * <h4>Redis 구현 시 (Lua Script - 멱등성 보장)</h4>
     * <pre>
     * local removed = redis.call('LREM', inflight, 1, msgId)
     * if removed > 0 then
     *     redis.call('ZREM', inflight_ts, msgId)
     *     redis.call('HDEL', payload, msgId)
     * end
     * return removed
     * </pre>
     *
     * @param msgId 완료 처리할 메시지 ID
     */
    void ack(String msgId);

    /**
     * 처리 실패 - Delayed Retry 등록
     *
     * <h4>GPT-5 Iteration 4 (C): Retry ZSET 메커니즘</h4>
     * <ul>
     *   <li>retryCount < maxRetries: Retry ZSET에 등록 (exponential backoff)</li>
     *   <li>retryCount >= maxRetries: DLQ로 이동</li>
     * </ul>
     *
     * <h4>Redis 구현 시</h4>
     * <pre>
     * if retryCount >= MAX_RETRIES:
     *     RPUSH dlq msgId
     * else:
     *     delay = BASE_DELAY * (2 ^ retryCount)
     *     ZADD retry (now + delay) msgId
     * LREM inflight 1 msgId
     * ZREM inflight_ts msgId
     * </pre>
     *
     * @param msgId 실패한 메시지 ID
     * @param retryCount 현재 재시도 횟수
     */
    void nack(String msgId, int retryCount);

    /**
     * 대기 메시지 수 (Main Queue)
     *
     * @return 대기 중인 메시지 수
     */
    long getPendingCount();

    /**
     * 처리 중(INFLIGHT) 메시지 수
     *
     * @return INFLIGHT 상태 메시지 수
     */
    long getInflightCount();

    /**
     * Retry 대기 중 메시지 수
     *
     * @return Retry ZSET에 있는 메시지 수
     */
    long getRetryCount();

    /**
     * DLQ(Dead Letter Queue) 메시지 수
     *
     * @return DLQ에 있는 메시지 수
     */
    long getDlqCount();

    /**
     * 큐 타입 식별자
     *
     * <h4>모니터링 태그</h4>
     * <pre>
     * expectation.buffer.offer.success{strategy="REDIS_LIST"}
     * </pre>
     *
     * @return 큐 타입
     */
    QueueType getType();

    /**
     * 헬스체크
     *
     * <h4>Redis 구현 시</h4>
     * <pre>
     * try {
     *     redis.ping();
     *     return true;
     * } catch (Exception e) {
     *     return false;
     * }
     * </pre>
     *
     * @return true: 정상, false: 비정상
     */
    default boolean isHealthy() {
        return true;
    }

    /**
     * Shutdown 준비 - 새로운 publish 차단
     *
     * <h4>Graceful Shutdown 지원</h4>
     * <p>새로운 메시지 수신을 차단하고 기존 메시지 처리 완료 대기</p>
     */
    default void prepareShutdown() {
        // 기본 구현: no-op
    }

    /**
     * Shutdown 진행 중 여부 확인
     *
     * @return true: Shutdown 진행 중
     */
    default boolean isShuttingDown() {
        return false;
    }
}
