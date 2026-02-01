package maple.expectation.global.queue.expectation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.global.queue.QueueMessage;
import maple.expectation.global.queue.strategy.RedisBufferStrategy;
import maple.expectation.service.v4.buffer.ExpectationWriteTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 기반 Expectation Write-Behind 버퍼 (#271 V5 Stateless Architecture)
 *
 * <h3>역할</h3>
 * <p>Redis Reliable Queue 패턴을 사용하여 Expectation 계산 결과를 버퍼링합니다.
 * Scale-out 환경에서도 단일 진실 소스(Single Source of Truth)를 보장합니다.</p>
 *
 * <h3>기존 ExpectationWriteBackBuffer 대비 개선</h3>
 * <ul>
 *   <li>기존: ConcurrentLinkedQueue (In-Memory) → 인스턴스별 분산</li>
 *   <li>개선: Redis LIST (Distributed) → 전역 일관성</li>
 * </ul>
 *
 * <h3>Redis 구조 (RedisBufferStrategy 위임)</h3>
 * <pre>
 * {expectation}:buffer            (LIST) - Main Queue
 * {expectation}:buffer:inflight   (LIST) - Processing Queue
 * {expectation}:buffer:inflight:ts (ZSET) - Timeout Tracking
 * {expectation}:buffer:payload    (HASH) - Payload Store
 * {expectation}:buffer:retry      (ZSET) - Delayed Retry
 * {expectation}:buffer:dlq        (LIST) - Dead Letter Queue
 * </pre>
 *
 * <h3>기존 API 호환성</h3>
 * <p>기존 ExpectationWriteBackBuffer와 동일한 인터페이스 제공:
 * <ul>
 *   <li>offer(characterId, presets) → publish to Redis</li>
 *   <li>drain(maxBatchSize) → consume from Redis</li>
 *   <li>getPendingCount() → pending + inflight count</li>
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Blue (Architect): RedisBufferStrategy 위임으로 코드 재사용</li>
 *   <li>Green (Performance): Lua Script로 RTT 최소화</li>
 *   <li>Red (SRE): INFLIGHT 패턴으로 메시지 유실 방지</li>
 *   <li>Purple (Auditor): At-Least-Once 전달 보장</li>
 *   <li>Yellow (QA): 기존 API 100% 호환</li>
 * </ul>
 *
 * @see RedisBufferStrategy Redis 기반 메시지 큐 전략
 */
@Slf4j
public class RedisExpectationWriteBackBuffer {

    private final RedisBufferStrategy<ExpectationWriteTask> redisStrategy;
    private final MeterRegistry meterRegistry;

    /**
     * CAS 재시도 카운터 (메트릭용)
     *
     * <p>Redis는 원자적이므로 CAS가 필요 없지만,
     * 기존 인터페이스 호환을 위해 메트릭만 유지</p>
     *
     * <h4>Issue #283 P1-11: Scale-out 분산 안전성</h4>
     * <p>인스턴스 로컬 캐시 값으로, 실제 카운트는 Redis에서 조회합니다.
     * 각 인스턴스가 독립적으로 메트릭을 수집하므로 분산 환경에서 안전합니다.</p>
     */
    private final AtomicInteger pendingCountCache = new AtomicInteger(0);

    /**
     * Inflight 메시지 추적 (ACK용)
     *
     * <p>consume()으로 가져온 메시지는 ACK 전까지 INFLIGHT 상태</p>
     *
     * <h4>Issue #283 P1-11: synchronized 블록 분산 안전성 분석</h4>
     * <p>이 리스트의 {@code synchronized} 블록은 <b>인스턴스 로컬 스레드 안전성</b>을 위한 것입니다.
     * 각 인스턴스는 자신이 consume한 메시지만 추적하며, 다른 인스턴스와 공유하지 않습니다.</p>
     * <ul>
     *   <li>drain() → 이 인스턴스가 Redis에서 가져온 메시지 ID를 로컬에 기록</li>
     *   <li>ackAll()/nackAll() → 로컬에 기록된 메시지 ID로 Redis에 ACK/NACK 전송</li>
     *   <li>메시지 소유권: Redis INFLIGHT 큐가 인스턴스별 소유권을 보장</li>
     * </ul>
     * <p><b>결론: synchronized는 인스턴스 내부 drain/ack 스레드 동기화용.
     * Redis INFLIGHT 패턴이 분산 수준의 메시지 안전성을 이미 보장하므로 추가 변환 불필요.</b></p>
     */
    private final List<String> inflightMessageIds = new ArrayList<>();

    public RedisExpectationWriteBackBuffer(
            RedisBufferStrategy<ExpectationWriteTask> redisStrategy,
            MeterRegistry meterRegistry) {
        this.redisStrategy = redisStrategy;
        this.meterRegistry = meterRegistry;

        registerMetrics();
        log.info("[RedisExpectationWriteBackBuffer] Initialized with Redis strategy");
    }

    private void registerMetrics() {
        // 대기 중인 메시지 수 (Redis Main Queue)
        Gauge.builder("expectation.buffer.redis.pending", this::getPendingCount)
                .description("Redis 버퍼의 대기 메시지 수")
                .register(meterRegistry);

        // 처리 중인 메시지 수 (Redis INFLIGHT)
        Gauge.builder("expectation.buffer.redis.inflight", this::getInflightCount)
                .description("Redis 버퍼의 처리 중 메시지 수")
                .register(meterRegistry);

        // 재시도 대기 메시지 수
        Gauge.builder("expectation.buffer.redis.retry", this::getRetryCount)
                .description("Redis 버퍼의 재시도 대기 메시지 수")
                .register(meterRegistry);

        // DLQ 메시지 수
        Gauge.builder("expectation.buffer.redis.dlq", this::getDlqCount)
                .description("Redis 버퍼의 DLQ 메시지 수")
                .register(meterRegistry);
    }

    /**
     * 프리셋 결과를 버퍼에 추가
     *
     * <p>Redis Reliable Queue에 발행합니다. CAS 백프레셔는 Redis에서
     * 처리되므로 항상 성공합니다 (Redis 장애 시 제외).</p>
     *
     * @param characterId 캐릭터 ID
     * @param presets 프리셋 결과 목록
     * @return true: 버퍼링 성공, false: Shutdown 중 또는 Redis 장애
     */
    public boolean offer(Long characterId, List<PresetExpectation> presets) {
        if (redisStrategy.isShuttingDown()) {
            meterRegistry.counter("expectation.buffer.rejected.shutdown").increment();
            log.debug("[ExpectationBuffer] Rejected during shutdown: characterId={}", characterId);
            return false;
        }

        int publishedCount = 0;
        for (PresetExpectation preset : presets) {
            ExpectationWriteTask task = ExpectationWriteTask.from(characterId, preset);
            String msgId = redisStrategy.publish(task);

            if (msgId != null) {
                publishedCount++;
            } else {
                meterRegistry.counter("expectation.buffer.publish.failed").increment();
                log.warn("[ExpectationBuffer] Failed to publish task for character {} preset {}",
                        characterId, preset.getPresetNo());
            }
        }

        if (publishedCount > 0) {
            meterRegistry.counter("expectation.buffer.publish.success").increment(publishedCount);
            log.debug("[ExpectationBuffer] Buffered {} presets for character {}", publishedCount, characterId);
        }

        return publishedCount == presets.size();
    }

    /**
     * 버퍼에서 배치 크기만큼 작업 추출
     *
     * <p>Redis INFLIGHT 패턴으로 메시지를 가져옵니다.
     * 반환된 메시지는 ACK 전까지 INFLIGHT 상태입니다.</p>
     *
     * <p><b>중요:</b> 처리 완료 후 반드시 {@link #ackAll()}을 호출해야 합니다.</p>
     *
     * @param maxBatchSize 최대 배치 크기
     * @return 추출된 작업 목록 (빈 리스트 가능)
     */
    public List<ExpectationWriteTask> drain(int maxBatchSize) {
        List<QueueMessage<ExpectationWriteTask>> messages = redisStrategy.consume(maxBatchSize);

        List<ExpectationWriteTask> tasks = new ArrayList<>(messages.size());

        synchronized (inflightMessageIds) {
            inflightMessageIds.clear();
            for (QueueMessage<ExpectationWriteTask> message : messages) {
                tasks.add(message.payload());
                inflightMessageIds.add(message.msgId());
            }
        }

        if (!tasks.isEmpty()) {
            log.debug("[ExpectationBuffer] Drained {} tasks from Redis", tasks.size());
        }

        return tasks;
    }

    /**
     * drain()으로 가져온 모든 메시지 ACK
     *
     * <p>drain() 후 처리가 완료되면 반드시 호출해야 합니다.</p>
     */
    public void ackAll() {
        List<String> toAck;
        synchronized (inflightMessageIds) {
            toAck = new ArrayList<>(inflightMessageIds);
            inflightMessageIds.clear();
        }

        for (String msgId : toAck) {
            redisStrategy.ack(msgId);
        }

        if (!toAck.isEmpty()) {
            log.debug("[ExpectationBuffer] ACKed {} messages", toAck.size());
        }
    }

    /**
     * drain()으로 가져온 모든 메시지 NACK (재시도)
     *
     * <p>처리 실패 시 호출합니다. 메시지는 Retry Queue로 이동합니다.</p>
     */
    public void nackAll() {
        List<String> toNack;
        synchronized (inflightMessageIds) {
            toNack = new ArrayList<>(inflightMessageIds);
            inflightMessageIds.clear();
        }

        for (String msgId : toNack) {
            redisStrategy.nack(msgId, 0);  // retryCount는 RedisBufferStrategy에서 관리
        }

        if (!toNack.isEmpty()) {
            log.warn("[ExpectationBuffer] NACKed {} messages for retry", toNack.size());
        }
    }

    /**
     * 대기 중인 작업 수 조회 (Main Queue)
     */
    public int getPendingCount() {
        return (int) redisStrategy.getPendingCount();
    }

    /**
     * 처리 중인 작업 수 조회 (INFLIGHT)
     */
    public long getInflightCount() {
        return redisStrategy.getInflightCount();
    }

    /**
     * 재시도 대기 작업 수 조회 (Retry Queue)
     */
    public long getRetryCount() {
        return redisStrategy.getRetryCount();
    }

    /**
     * DLQ 작업 수 조회
     */
    public long getDlqCount() {
        return redisStrategy.getDlqCount();
    }

    /**
     * 버퍼가 비어있는지 확인
     */
    public boolean isEmpty() {
        return getPendingCount() == 0 && getInflightCount() == 0;
    }

    // ==================== Shutdown ====================

    /**
     * Shutdown 준비 단계 - 새로운 offer 차단
     */
    public void prepareShutdown() {
        redisStrategy.prepareShutdown();
        log.info("[ExpectationBuffer] Shutdown prepared - new offers will be rejected");
    }

    /**
     * Shutdown 진행 중 여부 확인
     */
    public boolean isShuttingDown() {
        return redisStrategy.isShuttingDown();
    }

    /**
     * 진행 중인 offer 작업 완료 대기
     *
     * <p>Redis 기반이므로 로컬 Phaser 대기는 불필요합니다.
     * 메시지는 이미 Redis에 안전하게 저장되어 있습니다.</p>
     *
     * @param timeout 최대 대기 시간 (현재 구현에서는 사용하지 않음)
     * @return true: 항상 성공 (Redis 기반)
     */
    public boolean awaitPendingOffers(Duration timeout) {
        // Redis 기반이므로 로컬 대기 불필요
        // 메시지는 이미 Redis에 저장됨
        log.info("[ExpectationBuffer] Redis-based buffer - no local pending offers to wait");
        return true;
    }

    /**
     * Shutdown 대기 타임아웃 조회
     */
    public Duration getShutdownAwaitTimeout() {
        return Duration.ofSeconds(30);  // Default timeout
    }

    // ==================== Health Check ====================

    /**
     * Redis 연결 상태 확인
     */
    public boolean isHealthy() {
        return redisStrategy.isHealthy();
    }

    // ==================== Re-drive & Retry ====================

    /**
     * 만료된 INFLIGHT 메시지 복구
     *
     * @param timeoutMs 타임아웃 (ms)
     * @param limit 최대 복구 개수
     * @return 복구된 메시지 수
     */
    public int redriveExpiredMessages(long timeoutMs, int limit) {
        List<String> expired = redisStrategy.getExpiredInflightMessages(timeoutMs, limit);
        int redriven = 0;

        for (String msgId : expired) {
            if (redisStrategy.redrive(msgId)) {
                redriven++;
            }
        }

        if (redriven > 0) {
            log.info("[ExpectationBuffer] Redriven {} expired messages", redriven);
        }

        return redriven;
    }

    /**
     * Retry Queue 처리
     *
     * @param limit 최대 처리 개수
     * @return 처리된 메시지 수
     */
    public int processRetryQueue(int limit) {
        List<String> processed = redisStrategy.processRetryQueue(limit);

        if (!processed.isEmpty()) {
            log.info("[ExpectationBuffer] Processed {} retry messages", processed.size());
        }

        return processed.size();
    }
}
